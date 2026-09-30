# Step 11 Specification — Eval Harness & Reports

Companion to `v1.0-scope.md` (§4, §6, §10.20, §10.26, §10.34) and `v1.0-build-steps.md` (Step 11).
Order and principles follow `TIGERSTYLE.md`: explicit failure modes, no silent swallows,
no big-bang integrations, deterministically testable milestones (M0–M4).

---

## Part 0 — Goal & Mental Model

### Goal
Implement the evaluation harness, automated scoring engine, regression table aggregator, and
self-contained HTML report generator. At Step 11, every diagnostic run is scored deterministically
against ground truth from Evidence DB alone, results are comparable across prompt iterations,
and reports are generated with zero manual collation.

### The Mental Model

```
Evidence DB (Postgres / Repositories)
      │
      │ (read-only query: run, iteration, load_report, target, trajectory_event)
      ▼
┌─────────────────────── eval Module (io.diag.eval) ───────────────────────┐
│                                                                          │
│  1. EvalScorer (§6.2)                                                    │
│     • Accuracy: final-kept hypothesis category vs. ground_truth          │
│     • Effectiveness: p95 delta vs. baseline beyond noise floor           │
│     • Efficiency: iterations count, tokens (in + out), $ cost            │
│                                                                          │
│  2. RegressionTableService (§10.20)                                      │
│     • Grouping: (prompt_hash, model, aggregator_version) × targetId      │
│     • Metric view: "Did my prompt tweak or model update improve score?"  │
│                                                                          │
│  3. HtmlReportGenerator (§10.26)                                         │
│     • Self-contained offline HTML file per run (no external CDNs)        │
│     • Timeline, before/after metric bars, hypothesis ledger, trajectory  │
│                                                                          │
│  4. MatrixRunner (§6.1)                                                  │
│     • Executes full matrix (S1–S4) sequentially                          │
│     • Baseline cache integration (zero redundant k6 runs)                │
│     • Git isolation via SeededTarget                                     │
└──────────────────────────────────────────────────────────────────────────┘
```

### The Architectural Boundary ("The Judge Stays Out of the Judged")
- `eval` sits at the top of the Maven dependency DAG:
  `evidence` ← `target-runner` ← `agent-core` ← `eval`.
- Nothing depends on `eval`.
- `eval` judges runs by reading **only the Evidence DB and persisted artifacts**, never internal in-memory loop state.

---

## Part 1 — Key Architectural Decisions

### D1: Read-Only DB Coupling
- `EvalScorer` queries `RunRepository`, `IterationRepository`, `TargetRepository`, `LoadReportRepository`, and `TrajectoryEventRepository`.
- Scoring can be performed on live runs, completed runs, or retroactively on historical runs.
- Scoring produces immutable records (`RunScore`, `MatrixScoreReport`) that do not mutate the underlying evidence rows.

### D2: Diagnostic Accuracy Contract (§6.2)
- Accuracy is judged by comparing the diagnosed hypothesis category against the target's `ground_truth_category`:
  - If the run has ≥1 `KEPT` iteration: use the **final kept iteration's hypothesis category**.
  - If the run has 0 `KEPT` iterations (all reverted or wasted): use the **last iteration's hypothesis category**.
  - Accuracy is a boolean (`accurate = diagnosedCategory.equalsIgnoreCase(groundTruthCategory)`).

### D3: Effectiveness & Convergence Contract (§6.2, §10.5)
- Baseline p95 and noise floor are read from the run row (`baseline_p95_ms`, `noise_floor_ms`).
- Final p95 is read from the final kept iteration's load report (or baseline if nothing kept).
- **Effectiveness delta**: `p95Delta = baselineP95 - finalP95`.
- **Convergence criterion**:
  `converged = (p95Delta > noiseFloorMs) && (finalFailRate <= baselineFailRate + 0.001)`.
- If RPS improved beyond its noise floor (`(finalRps - baselineRps) > noiseFloorRps`), this is also recorded as `rpsImproved = true`.

### D4: Attribution & Regression Table Grouping (§10.20, §10.34)
- Regression table groups runs by:
  `RegressionKey = (prompt_hash, model, aggregator_version)`.
- For each `(RegressionKey, targetId)` tuple, aggregate:
  - `runCount`: total runs in group
  - `accuracyRate`: percentage of runs where `accurate == true`
  - `convergenceRate`: percentage of runs where `converged == true`
  - `meanP95DeltaMs`: average p95 reduction
  - `meanRpsDelta`: average throughput improvement
  - `meanIterations`: average iterations consumed
  - `meanTokens`: average total tokens (`tokens_in + tokens_out`)
  - `meanCostUsd`: average dollar cost

### D5: Self-Contained Offline HTML Report (§10.26)
- The HTML report must open standalone in any browser with **zero network requests** (no CDNs, no remote fonts, no external JS libraries).
- Visual charts (e.g. before vs. after latency bars, noise floor thresholds) are rendered using inline SVG and CSS flexbox.
- Links to artifacts (`profile.jfr`, `k6-summary.json`) use relative paths or explicit file URIs.

### D6: Matrix Runner Orchestration (§6.1)
- `MatrixRunner` executes the matrix (S1–S4) through `AgentLoop`.
- Coordinates target preparation with `SeededTarget` and `TargetRegistry`.
- Employs `BaselineCache` so repeat matrix executions skip redundant baseline cycles.

---

## Part 2 — Domain Models & Records

### 1. `RunScore` (Immutable Value Record)
```java
public record RunScore(
        String runId,
        String targetId,
        String status,
        String groundTruthCategory,
        String diagnosedCategory,
        boolean accurate,
        double baselineP95Ms,
        double finalP95Ms,
        double p95DeltaMs,
        double noiseFloorMs,
        boolean converged,
        double baselineRps,
        double finalRps,
        double rpsDelta,
        double noiseFloorRps,
        boolean rpsImproved,
        int iterationsUsed,
        long totalTokens,
        BigDecimal totalCostUsd,
        Instant startedAt,
        Instant finishedAt
) {
    // Non-null assertions per TigerStyle Rule 4
}
```

### 2. `RegressionKey` & `RegressionRow`
```java
public record RegressionKey(
        String promptHash,
        String model,
        String aggregatorVersion
) {}

public record RegressionRow(
        RegressionKey key,
        String targetId,
        int runCount,
        double accuracyRate,
        double convergenceRate,
        double meanP95DeltaMs,
        double meanRpsDelta,
        double meanIterations,
        long meanTokens,
        BigDecimal meanCostUsd
) {}
```

### 3. `MatrixScoreReport`
```java
public record MatrixScoreReport(
        int totalTargets,
        int accurateCount,
        double accuracyRate,
        int convergedCount,
        double convergenceRate,
        List<RunScore> runScores
) {
    public boolean passesV1Threshold() {
        // Scope §6: accuracy >= 70% and >= 2 of 4 targets converged
        return accuracyRate >= 0.70 && convergedCount >= 2;
    }
}
```

---

## Part 3 — Service Contracts

### 1. `EvalScorer`
```java
public interface EvalScorer {
    RunScore scoreRun(String runId);
    MatrixScoreReport scoreRuns(List<String> runIds);
}
```

### 2. `RegressionTableService`
```java
public interface RegressionTableService {
    List<RegressionRow> computeTable(List<String> runIds);
    String renderMarkdown(List<RegressionRow> rows);
}
```

### 3. `HtmlReportGenerator`
```java
public interface HtmlReportGenerator {
    String generateRunReport(String runId);
    Path exportRunReport(String runId, Path destinationFile) throws IOException;
    String generateMatrixReport(List<String> runIds);
}
```

### 4. `MatrixRunner`
```java
public interface MatrixRunner {
    MatrixScoreReport runMatrix(List<String> targetIds, LoopConfig loopConfig, GenParams genParams) throws Exception;
}
```

---

## Part 4 — Milestones & Verification Gates

### Milestone 0: Scoring Domain & Service (`M0`)
- **Goal**: Implement `RunScore`, `MatrixScoreReport`, and `EvalScorerImpl`.
- **Contract**:
  - Accurate calculation of accuracy, effectiveness, convergence, and efficiency from DB entities.
  - Fail-fast null checks on all public methods.
- **Verification Gate**: `EvalScorerTest` (mock/fixture data, zero Docker, zero network).

### Milestone 1: Regression Table Aggregator (`M1`)
- **Goal**: Implement `RegressionKey`, `RegressionRow`, and `RegressionTableService`.
- **Contract**:
  - Groups runs by `(prompt_hash, model, aggregator_version) × targetId`.
  - Generates clear Markdown regression tables for prompt comparison.
- **Verification Gate**: `RegressionTableTest` testing grouping and formatting across multiple prompt/model variations.

### Milestone 2: Self-Contained HTML Report Generator (`M2`)
- **Goal**: Implement `HtmlReportGenerator`.
- **Contract**:
  - Pure Java template/builder rendering single-file HTML.
  - Embedded CSS, inline SVG bar charts, timeline table, and trajectory log.
  - Zero external CDN or script dependencies.
- **Verification Gate**: `HtmlReportGeneratorTest` asserting valid HTML structure, complete tables, and file write.

### Milestone 3: Matrix Runner Orchestration (`M3`)
- **Goal**: Implement `MatrixRunner` coordinating sequential runs across targets S1–S4.
- **Contract**:
  - Leverages `TargetRegistry`, `SeededTarget`, `AgentLoop`, and `BaselineCache`.
  - Executes dry or live runs and collects `MatrixScoreReport`.
- **Verification Gate**: `MatrixRunnerTest` with fake pipeline and scripted turns.

### Milestone 4: Full Step 11 Verify Gate (`Step11VerifyGate`) (`M4`)
- **Goal**: End-to-end integration test of scoring, regression comparison, and report generation.
- **Verification Gate**: `mvn test -pl eval -Dtest=Step11VerifyGate` green.
  - Score verified against ground truth.
  - Two runs with differing prompt hashes produce two distinct regression groups.
  - HTML reports generated and verified on disk.

---

## Part 5 — TigerStyle Enforcement Checklist for Step 11
- [ ] No stream pipelines longer than 3 operations without intermediate named variables.
- [ ] Every `catch` block handles, compensates (with comment), or rethrows.
- [ ] Public methods validate non-null inputs (`Objects.requireNonNull`).
- [ ] No external CDN dependencies in HTML reports (fully offline self-contained).
- [ ] Scorer reads only from DB repositories (zero in-memory coupling with loop internals).
