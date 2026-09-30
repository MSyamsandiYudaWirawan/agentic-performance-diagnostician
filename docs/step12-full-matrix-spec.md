# Step 12 Specification — Full Matrix Run & Success Criterion Verification

Companion to `v1.0-scope.md` (§6, §10.5, §10.19, §10.20, §10.25, §10.26) and `v1.0-build-steps.md` (Step 12).
Order and principles follow `TIGERSTYLE.md`: explicit failure modes, no silent swallows,
no big-bang integrations, deterministically testable milestones (M0–M4).

---

## Part 0 — Goal & Mental Model

### Goal
Execute the autonomous diagnostic loop across all canonical targets (**S1–S4**), proving the
**v1.0 Success Criterion** (§6):
1. **Matrix autonomy**: Sequential execution across targets S1–S4 (≤5 iterations each, guardrails active),
   evidence complete in Postgres, artifacts hashed on disk.
2. **Automated scoring**: Diagnosis accuracy ≥ 70% (≥ 3 of 4 correct) and ≥ 2 of 4 targets converged beyond noise floor.
3. **Zero manual collation**: Formatted regression table (`regression-table.md`) and self-contained HTML reports
   (`matrix-report.html`, `run-<id>.html`) generated straight from the Evidence DB.
4. **Guardrails & resume proven**: Aborted runs retain partial evidence without corruption; interrupted runs resume
   cleanly from their last kept iteration.
5. **Triage playbook**: Deterministic debugging order (*JFR quality → prompt → tool results → model*) recorded in
   the regression table.

### The Mental Model

```
                               ┌──────────────────────────────────────────────┐
                               │           MatrixCli / MatrixRunner           │
                               └──────────────────────┬───────────────────────┘
                                                      │
                       ┌──────────────────────────────┼──────────────────────────────┐
                       │                              │                              │
                       ▼                              ▼                              ▼
                 Target S1                      Target S2                      Target S3/S4
             (Stock PetClinic)             (Hikari Starvation)              (JVM / Monitor)
            Ground Truth: H5 lock          Ground Truth: H2 pool         Ground Truth: H3/H5 lock
            Baseline Cache: Miss/Hit       Baseline Cache: Miss/Hit       Baseline Cache: Miss/Hit
                       │                              │                              │
                       ▼                              ▼                              ▼
                 AgentLoop                      AgentLoop                      AgentLoop
             (≤5 iterations)                (≤5 iterations)                (≤5 iterations)
             Guardrails: ON                 Guardrails: ON                 Guardrails: ON
                       │                              │                              │
                       └──────────────────────────────┼──────────────────────────────┘
                                                      │
                                                      ▼
                                       ┌──────────────────────────────┐
                                       │     Evidence DB (Postgres)   │
                                       │   Run / Iteration / Load /   │
                                       │   JFR / Trajectory / Target  │
                                       └──────────────┬───────────────┘
                                                      │
                                                      ▼
                                       ┌──────────────────────────────┐
                                       │          EvalScorer          │
                                       │    MatrixScoreReport (S1-S4) │
                                       │    Accuracy ≥ 70%, Conv ≥ 2  │
                                       └──────────────┬───────────────┘
                                                      │
                       ┌──────────────────────────────┴──────────────────────────────┐
                       ▼                                                             ▼
         RegressionTableService                                            HtmlReportGenerator
      • prompt_hash comparison                                          • matrix-report.html (Scorecard)
      • regression-table.md                                             • run-<id>.html (Inline SVGs)
```

---

## Part 1 — Key Architectural Decisions

### D1: Matrix CLI & Executable Entry Point
- Provide a clear CLI entrypoint (`io.diag.eval.cli.MatrixCli`) that can run from `mvn` or `java -jar`.
- Configuration via CLI arguments or environment variables:
  - `--targets=S1,S2,S3,S4` (`DIAG_TARGETS`)
  - `--provider=anthropic`, `--model=claude-3-7-sonnet` (`DIAG_MODEL`)
  - `--reports-dir=target/reports` (`DIAG_REPORTS_DIR`)
  - `--dry-run=true|false` (`DIAG_DRY_RUN`)
  - `--max-iterations=5`, `--max-wall-ms=1800000`
- Automatically writes:
  - `target/reports/matrix-report.html`
  - `target/reports/regression-table.md`
  - `target/reports/runs/run-<runId>.html` (per target run)

### D2: Dual Execution Strategy (Live vs. Simulated CI)
- **Live Mode**: Uses real `DockerTargetPipeline` with k6 and JFR containers + live LLM API via Spring AI.
- **Verification Mode (Testable CI)**: Uses simulated `TargetPipeline` and deterministic scripted turns to
  verify matrix orchestration, scoring mathematics, baseline cache skips, guardrails, and resume
  in 2–5 seconds with zero Docker overhead and zero external network flakiness.

### D3: Guardrail Enforcement & Partial Evidence Retention (§10.25)
- When a run breaches a guardrail (max iterations, max wall clock, max tokens, max cost):
  1. `GuardrailAbortedException` is raised.
  2. Run status is safely transitioned to `ABORTED`.
  3. All completed iterations and trajectory events remain persisted in Evidence DB.
  4. Matrix runner captures the aborted run and includes its partial score in `MatrixScoreReport` without crashing.

### D4: Resume Recovery Hygiene (§10.19, §10.25)
- A killed run (`RUNNING` or `ABORTED` with `DIAG_RESUME_ABORTED=true`) can be resumed using `AgentLoop.start(preCreatedRunId)`.
- Revert-if-dirty contract: verifies target repository SHA matches `lastKeptSha`; if not, reverts to `lastKeptSha` before continuing iteration loop.
- Reloads existing baseline from DB load reports; does NOT rerun baseline benchmarks.

### D5: Diagnostic Triage Playbook (§6, §10.20)
- When a run fails (inaccurate diagnosis or lack of convergence), automated triage evaluates the failure mode:
  1. **JFR Quality**: Did JFR miss lock events or sample insufficient frames?
  2. **Model Classification**: Did the model select an inaccurate hypothesis category?
  3. **Fix Selection**: Did the model pick the wrong fix template or invalid parameters?
  4. **Keep-Rule Rejection**: Did the change cause latency/throughput regression or breach noise floor?
- Triage findings are summarized in both Markdown regression tables and HTML reports.

---

## Part 2 — Domain Models & Service Contracts

### 1. `MatrixConfig` (Immutable CLI & Runner Configuration)
```java
public record MatrixConfig(
        List<String> targetIds,
        Path reportsDir,
        LoopConfig loopConfig,
        GenParams genParams,
        boolean dryRun
) {
    public MatrixConfig {
        Objects.requireNonNull(targetIds, "targetIds must not be null");
        Objects.requireNonNull(reportsDir, "reportsDir must not be null");
        Objects.requireNonNull(loopConfig, "loopConfig must not be null");
        Objects.requireNonNull(genParams, "genParams must not be null");
    }

    public static MatrixConfig fromEnvAndArgs(String[] args) {
        // Safe parsing with defaults
    }
}
```

### 2. `DiagnosticTriage`
```java
public record DiagnosticTriage(
        String runId,
        String targetId,
        String failureStage, // JFR_QUALITY | MODEL_DIAGNOSIS | TEMPLATE_SELECTION | KEEP_RULE_REJECTED | GUARDRAIL_EXCEEDED | NONE
        String detail,
        String recommendedAction
) {}

public interface DiagnosticTriageService {
    DiagnosticTriage analyze(String runId);
}
```

### 3. `MatrixExecutionResult`
```java
public record MatrixExecutionResult(
        MatrixScoreReport scoreReport,
        List<RegressionRow> regressionTable,
        List<DiagnosticTriage> triages,
        Path matrixReportHtmlPath,
        Path regressionTableMdPath
) {
    public boolean isSuccess() {
        return scoreReport.passesV1Threshold();
    }
}
```

---

## Part 3 — Milestones & Verification Gates

### Milestone 0: Matrix CLI & Application Runner (`M0`)
- **Goal**: Implement `MatrixCli` and `MatrixConfig` in `eval`.
- **Contract**:
  - Parse CLI flags (`--targets`, `--reports-dir`, `--provider`, `--model`, `--dry-run`).
  - Run matrix orchestration, trigger scoring, and export reports to disk.
  - Return exit code `0` on v1 threshold pass, `1` on threshold failure, `2` on fatal error.
- **Verification Gate**: `MatrixCliTest` testing argument parsing, exit codes, and file emission.

### Milestone 1: Guardrail & Resume Verification (`M1`)
- **Goal**: Verify guardrail caps and resume recovery within matrix execution.
- **Contract**:
  - Guardrail cap violation marks run as `ABORTED` with partial evidence preserved.
  - Killed run resumes from `lastKeptSha` and completes remaining iterations without rerunning baseline.
- **Verification Gate**: `MatrixGuardrailResumeTest`.

### Milestone 2: Diagnostic Triage Advisor (`M2`)
- **Goal**: Implement `DiagnosticTriageService` diagnosing root failure causes.
- **Contract**:
  - Deterministic evaluation: JFR quality → model hypothesis → fix template → keep rule.
  - Emits recommendations into HTML scorecard and regression markdown.
- **Verification Gate**: `DiagnosticTriageTest`.

### Milestone 3: End-to-End Success Criterion Evaluation (`M3`)
- **Goal**: Full matrix evaluation across canonical targets S1–S4.
- **Contract**:
  - All 4 targets execute autonomously through the loop.
  - Evaluates scope §6: accuracy ≥ 70%, convergence count ≥ 2.
  - Reports generated without manual collation.
- **Verification Gate**: `MatrixEvaluationTest`.

### Milestone 4: Full Step 12 Capstone Verify Gate (`Step12VerifyGate`) (`M4`)
- **Goal**: Master integration test verifying v1.0 completion.
- **Verification Gate**: `mvn test -pl eval -Dtest=Step12VerifyGate` green.
  - S1–S4 sequential execution with baseline caching.
  - Automated scoring meeting v1.0 threshold.
  - Standalone HTML reports & regression tables written to disk.
  - Guardrail safety and resume proven.

---

## Part 4 — TigerStyle Enforcement Checklist for Step 12
- [ ] No stream pipelines longer than 3 operations without intermediate named variables.
- [ ] Every `catch` block handles, compensates (with comment), or rethrows.
- [ ] Public methods validate non-null inputs (`Objects.requireNonNull`).
- [ ] Zero external CDN dependencies in generated HTML reports (strictly offline self-contained).
- [ ] Scoring reads exclusively from DB entities (eval judge stays out of the judged).
- [ ] Working tree hygiene preserved across all target runs (`git status --porcelain` clean).
