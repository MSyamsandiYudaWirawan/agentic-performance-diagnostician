# Agentic Performance Diagnostician

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-blue.svg)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17%20%2B%20pgvector-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-v1.1%20Episodic%20RAG%20%2B%20CallAdvisor%20Onion-success.svg)](#next-phase-plan-the-3-stage-ai-engineering-roadmap)

An autonomous AI agent system designed to diagnose performance bottlenecks in Java/Spring services, apply targeted code and configuration optimizations, and rigorously prove improvement through automated load benchmarks and Java Flight Recorder (JFR) profiling.

```
+---------------------------------------------------------------------------------------------------+
|                                  AUTONOMOUS DIAGNOSTIC LOOP                                       |
|                                                                                                   |
|  [3x Baseline Run] ──> [Noise Floor Calc] ──> [LLM Diagnosis & Hypothesis] ──> [Template Select]  |
|                                                                                       │           |
|  [Re-Benchmark]    <── [Smoke Gate Pass]  <── [Docker Rebuild & Test]      <── [Apply Change]     |
|         │                                                                                         |
|         ▼                                                                                         |
|  [JFR Profiling]   ──> [Keep-Rule v2 Check] ─┬─> (Kept: Commit SHA & Progress Ledger)             |
|                                              └─> (Revert: Reset Hard & Log Wasted)                |
|                                                                                       │           |
|  [Offline HTML / Markdown Reports] <── [Automated Eval Scoring] <── [Loop Complete or Aborted]    |
+---------------------------------------------------------------------------------------------------+
```

---

## Table of Contents

1. [Core Design Principles](#core-design-principles)
2. [System Architecture](#system-architecture)
3. [The Autonomous Agent Loop & 5-Layer `CallAdvisor` Onion](#the-autonomous-agent-loop--5-layer-calladvisor-onion)
4. [Episodic Vector Memory & Zero-Leakage RAG (`pgvector`)](#episodic-vector-memory--zero-leakage-rag-pgvector)
5. [Keep-Rule v2 & Saddle Navigation](#keep-rule-v2--saddle-navigation)
6. [Canonical Target Matrix (Ground Truth)](#canonical-target-matrix-ground-truth)
7. [Evaluation Scoring & Diagnostic Triage](#evaluation-scoring--diagnostic-triage)
8. [Reporting Pipeline (Zero Manual Collation)](#reporting-pipeline-zero-manual-collation)
9. [Module Structure](#module-structure)
10. [CLI Usage & Getting Started](#cli-usage--getting-started)
11. [Next Phase Plan: The 3-Stage AI Engineering Roadmap](#next-phase-plan-the-3-stage-ai-engineering-roadmap)

---

## Core Design Principles

Traditional LLM coding benchmarks evaluate code generation by passing static test suites. In production performance engineering, passing unit tests is merely table stakes—the real question is whether latency, throughput, and resource saturation actually improve under concurrent load without introducing subtle regressions.

This system is built around five core engineering tenets:

1. **AI Proposes, Pipeline Disposes**: The language model is never allowed to grade its own homework or declare victory based on vibes. Every proposed change is committed, built inside an isolated Docker envelope, verified by a strict smoke test, and re-benchmarked under identical load conditions.
2. **The Agent Cannot Edit Its Own Ruler**: Load profiles (k6 scripts), resource limits (CPU/memory constraints), and evaluation assertions live strictly outside the target repository. Post-run container inspections ensure that the execution envelope was never compromised.
3. **Pure Java Diagnostic Instrumentation**: Bottleneck detection relies on JDK-native Java Flight Recorder (JFR) data collected during peak load. Event parsers condense multi-megabyte binary recordings into focused 2–5 KB JSON summaries, identifying monitor locks, thread parks, database connection acquisition delays, and GC pressure with zero native agent overhead.
4. **Statistical Noise Floor Hygiene**: Benchmarks are inherently noisy. Before any optimizations are attempted, three baseline runs establish statistical noise floors:
   $$\text{Noise Floor} = \max(5\% \text{ of median}, \text{spread of 3 runs})$$
   A proposed fix is kept only if its improvement exceeds this measured noise floor.
5. **TigerStyle Engineering Standards**: The entire codebase enforces strict reliability constraints: fail-fast pre-conditions (`Objects.requireNonNull`), stream pipelines $\le 3$ operations for clarity, zero external CDN dependencies in generated reports, and clean repository isolation.

---

## System Architecture

The project is organized as a multi-module Maven monorepo separating concerns across data persistence, execution sandboxing, reasoning, and evaluation:

```
agentic-performance-diagnostician/
├── evidence/        # Postgres 17 + pgvector persistence, Flyway migrations, JFR/Load DTOs
├── target-runner/   # Docker container lifecycle, k6 load driver, JFR extraction, git patch applier
├── agent-core/      # Spring AI orchestration, 5-layer CallAdvisor onion, pgvector RAG memory, Keep-Rule v2
└── eval/            # Matrix CLI, automated ground-truth scoring, regression tables, HTML reports
```

### Component Interaction Flow

```
                      ┌────────────────────────────────────────┐
                      │              MatrixCli                 │
                      │  (CLI runner, exit codes, report dirs) │
                      └──────────────────┬─────────────────────┘
                                         │ invokes
                                         ▼
                      ┌────────────────────────────────────────┐
                      │             MatrixRunner               │
                      │  (Coordinates targets S1–S4 sequentially)
                      └──────────────────┬─────────────────────┘
                                         │ launches
                                         ▼
   ┌────────────────────────────────────────────────────────────────────────┐
   │                              AgentLoop                                 │
   │                                                                        │
   │   ┌───────────────┐     ┌───────────────┐      ┌───────────────────┐   │
   │   │  DecideTurn   │ ──> │ ChangeApplier │ ───> │   TargetPipeline  │   │
   │   │  (5-Layer     │     │ (Git patch    │      │  (Docker, smoke,  │   │
   │   │   Advisors +  │     │  & templates) │      │   k6, JFR harvest)│   │
   │   │   @Tool RAG)  │     └───────────────┘      └─────────┬─────────┘   │
   │   └───────▲───────┘                                      │             │
   │           │                                              │             │
   │           └─────────── [JFR Diff & Load Data] ───────────┘             │
   │                                                                        │
   │                         ┌───────────────┐                              │
   │                         │  KeepRule v2  │                              │
   │                         │ (Judge delta) │                              │
   │                         └───────┬───────┘                              │
   └─────────────────────────────────┼──────────────────────────────────────┘
                                     │ persists runs, traces & episodes
                                     ▼
                      ┌────────────────────────────────────────┐
                      │   EvidenceService & PgVectorStore      │
                      │ (Postgres 17 + Flyway + HNSW Vectors)  │
                      └──────────────────┬─────────────────────┘
                                         │ reads DB entities
                                         ▼
                      ┌────────────────────────────────────────┐
                      │        EvalScorer & TriageService      │
                      │  (Scores accuracy, convergence, triage)│
                      └──────────────────┬─────────────────────┘
                                         │ exports
                                         ▼
                      ┌────────────────────────────────────────┐
                      │    Reports: matrix-report.html,        │
                      │    regression-table.md, runs/*.html    │
                      └────────────────────────────────────────┘
```

---

## The Autonomous Agent Loop & 5-Layer `CallAdvisor` Onion

Each target optimization runs inside `AgentLoop` as an explicit, resilient state machine. Every iteration is bounded by strict budget and safety guardrails:

```
            ┌────────────────────────── Guardrail Checks ─────────────────────────┐
            │                     (Cost, Tokens, Iterations, Time)                 │
            ▼                                                                      │
      ┌───────────┐       ┌───────────┐       ┌───────────┐       ┌───────────┐    │
      │  DECIDE   │ ────> │   APPLY   │ ────> │  REBUILD  │ ────> │   SMOKE   │    │
      └─────┬─────┘       └─────┬─────┘       └─────┬─────┘       └─────┬─────┘    │
            │                   │                   │                   │          │
         Invalid             Template            Compile /           Endpoint      │
         Schema              Rejected            Test Fail           Unhealthy     │
            │                   │                   │                   │          │
            ▼                   ▼                   ▼                   ▼          │
       [WASTED]            [WASTED]            [REVERTED]          [REVERTED]      │
            │                   │                   │                   │          │
            └───────────────────┴─────────┬─────────┴───────────────────┘          │
                                          │                                        │
                                          ▼                                        │
                                  Revert Git Tree                                  │
                                          │                                        │
                                          ▼                                        │
                               ┌─────────────────────┐                             │
                               │     Next Turn       │                             │
                               └─────────────────────┘                             │
                                          ▲                                        │
                                          │                                        │
                                          │                                        │
      ┌───────────┐       ┌───────────┐   │                                        │
 ───> │  BENCH    │ ────> │   JUDGE   │ ──┴────────────────────────────────────────┘
      │ (k6 load) │       │ (KeepRule)│
      └───────────┘       └─────┬─────┘
                                │
               ┌────────────────┴────────────────┐
               ▼                                 ▼
           [KEPT]                            [REVERTED]
     Advance Last-Kept Sha              Git Reset Hard to Sha
     Index Episode in pgvector          Index Episode in pgvector
```

### 5-Layer `CallAdvisor` Governance Onion (`io.diag.agent.advisor`)
Rather than cluttering core business logic (`AgentLoop` / `SpringAiDecideTurn`) with retry loops, regex scrubbers, or token counters, every LLM call passes through a deterministic 5-layer Spring AI `CallAdvisor` interceptor chain ordered from outermost (`Order 10`) to innermost (`Order 50`):

1. **`Order 10` — `SecuritySanitizerAdvisor`**: Scrubs outgoing prompts and incoming responses against secret patterns (`sk-...`, `Bearer`, `password`, JDBC credentials) and blocks prompt-injection boundaries before any payload reaches or leaves the model.
2. **`Order 20` — `SchemaSelfHealingAdvisor`**: Validates the raw LLM output against `ProposedChangeSchemaValidator`. On structural JSON failure or markdown code-fence wrapping, automatically re-prompts the inner chain (up to `maxRetries=2`) with the exact parser error before `SpringAiDecideTurn` ever sees a failure.
3. **`Order 30` — `TokenBudgetAdvisor`**: Enforces both a per-call prompt ceiling (estimating `chars / 4` pre-flight) and cumulative run-wide token (`500,000`) and cost (`$10.00`) ceilings, aborting runaway tool-calling loops with a typed `BudgetExceededException`.
4. **`Order 40` — `TelemetryAndAuditAdvisor`**: Measures wall-clock latency, extracts prompt/completion token metadata, and writes structured forensic audit logs keyed by `runId` and `iterationNum`.
5. **`Order 50` — `InfraRetryAdvisor`**: Outermost-to-network transport boundary wrapping transient HTTP timeouts, rate limits (`429`), and `5xx` provider faults with exponential backoff.

### Loop-Level Safety & Resiliency
- **Ceilings**: Maximum dollar spend ($\$10.00$), token limit ($500{,}000$), wall-clock limit ($30\text{ min}$), and maximum iterations ($5$).
- **Partial Evidence Retention**: If a run hits an iteration ceiling or cost threshold, it transitions to `ABORTED` without discarding prior telemetry.
- **Checkpoint Resume**: Interrupted runs can be resumed (`AgentLoop.resume(runId)`). The loop detects existing baseline records from the database (avoiding redundant $3\times$ benchmarks), executes a `git reset --hard` to `lastKeptSha` to ensure clean working tree hygiene, and continues from iteration $n$.

---

## Episodic Vector Memory & Zero-Leakage RAG (`pgvector`)

Standard RAG pipelines retrieve static text chunks by cosine similarity. In an autonomous performance engineering agent, naive semantic search fails: code diffs do not semantically match runtime JFR stack traces, past failed experiments look identical to successful ones in vector space, and retrieving prior runs of the *same* benchmark target causes **benchmark data leakage**.

Step 13 (`io.diag.agent.memory`) implements **Episodic Vector Memory** backed by PostgreSQL 17 + `pgvector` (`HNSW` cosine index) with strict server-side invariants:

```
  [Write Path: Post-Judge Indexing]                [Read Path: Active @Tool Retrieval]
  Iteration (KEPT / REVERTED)                      LLM invokes @Tool queryPastFindings(...)
               │                                                    │
               ▼                                                    ▼
  SymptomSummarizer + DocumentFactory              6 Server-Side Guards (Flag, All-Null,
  (Embeds JFR symptom + hypothesis;                 400c Query & 50c Template Name Caps,
   stores up to 11 metadata keys)                   H1-H7 & KEPT/REVERTED Enum Checks)
               │                                                    │
               ▼                                                    ▼
     ┌───────────────────────────────────────────────────────────────┐
     │        PostgreSQL 17 + pgvector (vector_store table)          │
     │   Hybrid Search: Cosine Similarity (>= 0.65) + AST Filters    │
     │   • Benchmark Mode: targetId != currentTargetId (Zero Leakage)│
     │   • Production Mode: Cross-run + intra-service history allowed│
     └───────────────────────────────┬───────────────────────────────┘
                                     │
                                     ▼
                    3x Over-Fetch & Diversity Deduplication
                                     │
                                     ▼
                    MemoryContextFormatter (2x2 Advisory Matrix)
```

1. **Symptom-Forward Atomic Episode Indexing (`IterationDocumentFactory` & `SymptomSummarizer`)**:
   - Instead of chunking raw logs, each completed `KEPT` or `REVERTED` iteration is indexed as a single atomic `Document` using deterministic UUID v3 keys (`UUID(runId + ":" + iterationNum)`) for idempotent upserts.
   - Only the **symptom-forward narrative** (dominant JFR contention frames, p95/RPS baseline, hypothesis category `H1`–`H7`, and template summary) is embedded into vector space, while **up to 11 structured fields** (`runId`, `iterationN`, `targetId`, `category`, `template`, `outcome`, `keepType`, `embeddingModel`, `schemaVersion`, plus optional `deltaP95Ms`/`deltaRps` ledger deltas) are stored in `JSONB` metadata for exact AST filtering.
2. **Active `@Tool` Retrieval with 6 Server-Side Guards (`DiagnosticTools.queryPastFindings`)**:
   - Rather than bloating every prompt with unsolicited RAG dumps, the LLM actively invokes `queryPastFindings(symptomQuery, category, template, outcome)` when investigating a bottleneck.
   - Enforces **6 strict server-side guards**: (1) feature-flag gate, (2) all-null query rejection, (3) `400`-char `symptomQuery` cap, (4) `H1`–`H7` category enum validation, (5) `KEPT`/`REVERTED` outcome enum validation, and (6) `50`-char template-name cap. Loop-layer safety is separate and complementary: every decide turn gets a shared tool-call budget (`LoopConfig.toolCallBound`, default `10` via `DIAG_TOOL_BOUND`) enforced by `BoundedReadSource`, and `currentTargetId` is constructor-injected server-side state — never an LLM-visible parameter.
3. **Dual-Mode Benchmark Isolation (`DiagnosticMemoryRetriever`)**:
   - Uses Spring AI's programmatic `FilterExpressionBuilder` to combine cosine similarity (`threshold >= 0.65`) with metadata predicates (`outcome IN ['KEPT', 'REVERTED']` — excluding `WASTED` turns).
   - **Zero-Leakage Guarantee**: In benchmark isolation mode (`evaluationMode=true`), the retriever injects `ne("targetId", currentTargetId)` into the SQL/pgvector query AST, mathematically guaranteeing the agent can transfer cross-project architectural patterns without ever peeking at prior runs of the same benchmark target.
   - **3× Over-Fetch Deduplication**: Fetches `topK * 3` candidates and deduplicates by `(targetId : template)` (retaining only the highest-similarity episode per target/template pair) so one repetitive target never monopolizes all `topK` slots.
4. **The 2×2 Advisory Matrix (`MemoryContextFormatter`)**:
   - Formats retrieved episodes into an explicit decision matrix combining **Provenance (Same Service vs. Other Service) × Outcome (`KEPT` vs. `REVERTED`)**:
     - `KEPT` + Same Service: **`[BASELINE]`** — previously verified fix, already active on this target.
     - `REVERTED` + Same Service: **`[CAUTION]`** — attempted and reverted here; do not repeat verbatim without new evidence.
     - `KEPT` + Other Service: **`[PROVEN PATTERN]`** — succeeded on another service; high-confidence candidate to investigate.
     - `REVERTED` + Other Service: **`[OBSERVATION]`** — reverted elsewhere; check whether the root cause matches before attempting.

---

## Keep-Rule v2 & Saddle Navigation

In complex systems, performance optimizations rarely exhibit monotonic improvements across all percentiles simultaneously. 

### The Jar-Unpack Saddle Case Study
When running Spring Boot inside a container from an executable fat jar, classloader locks (`UrlJarFiles$Cache`) dominate under heavy concurrency ($71{,}000+$ lock contention events). Unpacking the fat jar (`jar-unpack` template) eliminates the classloader lock completely:
- **Throughput (RPS)**: $+47\%$
- **Median Latency (p50)**: $-87\%$
- **Tail Latency (p95)**: **$+24\%$** (temporary increase)

Why did p95 latency increase? Because eliminating the massive classloader lock bottleneck allowed requests to flood deeper into downstream application components, revealing a secondary queueing bottleneck. 

A naive greedy optimizer monitoring only p95 latency would reject this fix as a regression, reverting the commit and oscillating endlessly.

### The Two Evaluation Branches of Keep-Rule v2
1. **Classic Keep**:
   - RPS improves beyond the RPS noise floor, **OR**
   - p95 latency drops beyond the p95 noise floor (with bounded error rate).
2. **Verified-Mechanism Keep (Saddle Safe)**:
   - The LLM's explicit prediction is confirmed: the targeted JFR contention signal drops by $>50\%$.
   - At least one macro metric (RPS or p50) demonstrates meaningful gain beyond noise.
   - Tail latency regression (p95) remains bounded ($<50\%$).

This mathematical rule allows the agent to safely navigate saddles and unlock subsequent compound optimizations.

---

## Canonical Target Matrix (Ground Truth)

The evaluation suite benchmarks against four canonical targets exhibiting diverse real-world bottlenecks:

| Target ID | Architecture | Seeded Bottleneck | Ground Truth Category | Admitted Fix Template |
|---|---|---|---|---|
| **S1** | Spring Petclinic | Executable fat-jar classloading lock contention | `H5` (Classloader / Lock) | `jar-unpack` |
| **S2** | Petclinic + Hikari | Connection starvation (`maximumPoolSize=2` under 200 VUs) | `H2` (Connection Pool) | `hikari-pool-size` |
| **S3** | Quarkus Reactive | Memory pressure & GC thrashing (`-Xmx256m`) | `H3` (Garbage Collection) | `jvm-opts` |
| **S4** | Dropwizard / MVC | Synchronized method hotspot on shared business service | `H4` (Thread / Monitor Lock) | Concurrency refactoring |

---

## Evaluation Scoring & Diagnostic Triage

### Scoring Model (`EvalScorer`)
All scoring reads directly from immutable database records rather than relying on agent self-reporting:
- **Diagnostic Accuracy**: Does the agent's final hypothesis match the target's `groundTruthCategory`?
- **Convergence**: Did the agent produce at least one kept change that reduced p95 latency or increased RPS beyond the noise floor?
- **Efficiency**: Total iterations used ($\le 5$), prompt and completion tokens, and dollar cost.

### 4-Stage Diagnostic Failure Triage
When an evaluation run fails to converge or selects an incorrect hypothesis, the automated triage engine diagnoses the failure mode:

```
       [ JFR Quality Check ] ────── Fail ─────> STAGE_JFR_QUALITY (Missing signals / insufficient frames)
                 │ Pass
                 ▼
    [ Model Hypothesis Match ] ──── Fail ─────> STAGE_MODEL_DIAGNOSIS (Misclassified root cause)
                 │ Pass
                 ▼
    [ Fix Template Execution ] ──── Fail ─────> STAGE_TEMPLATE_SELECTION (Template rejected / wasted turn)
                 │ Pass
                 ▼
       [ Keep-Rule Check ] ──────── Fail ─────> STAGE_KEEP_RULE_REJECTED (Change below noise floor)
                 │ Pass
                 ▼
          [ STAGE_NONE ] (Accurately Diagnosed & Converged)
```

---

## Reporting Pipeline (Zero Manual Collation)

The evaluation runner outputs three self-contained artifacts directly to disk:

1. **`matrix-report.html`**: A single-file HTML dashboard featuring inline CSS, embedded SVG comparison charts (before vs. after latency and throughput), status badges, and execution metrics. It contains **zero external CDN links or remote fonts**, rendering instantly in offline and air-gapped environments.
2. **`regression-table.md`**: Markdown tables grouping runs by `(prompt_hash, model, aggregator_version) × targetId`. Includes statistical aggregations of mean latency delta, convergence rates, and the Diagnostic Failure Triage log.
3. **`runs/run-<id>.html`**: Per-run forensic reports containing interactive timeline visualizers, full prompt/response token trajectories, and step-by-step diff logs.

---

## Module Structure

```
agent-core/src/main/java/io/diag/agent/
├── advisor/
│   ├── SecuritySanitizerAdvisor.java  # Order 10: Prompt-injection & secret scrubber
│   ├── SchemaSelfHealingAdvisor.java  # Order 20: JSON schema validator & inner self-healing
│   ├── TokenBudgetAdvisor.java        # Order 30: Per-call & cumulative run token/cost ceiling
│   ├── TelemetryAndAuditAdvisor.java  # Order 40: Structured latency & token audit logging
│   └── InfraRetryAdvisor.java         # Order 50: Exponential backoff for transient LLM faults
├── memory/
│   ├── DiagnosticMemoryRetriever.java # Hybrid pgvector cosine + AST filter & 3x deduplication
│   ├── IterationDocumentFactory.java  # Deterministic UUID v3 episode builder (up to 11 metadata keys)
│   ├── IterationIndexer.java          # Post-judge indexing pipeline
│   ├── MemoryConfig.java              # Feature flag, topK, thresholds, eval mode
│   ├── MemoryContextFormatter.java    # 2x2 Advisory Matrix (Provenance × Outcome)
│   ├── MemoryQuery.java               # Read-path query record (symptom/category/template/outcome)
│   └── SymptomSummarizer.java         # Symptom-forward narrative synthesizer
├── loop/
│   ├── AgentLoop.java                 # Autonomous DECIDE -> APPLY -> BENCH -> JUDGE state machine
│   └── KeepRuleV2.java                # Classic + Verified-Mechanism saddle-safe evaluation rule
└── turn/
    ├── SpringAiChatPort.java          # Spring AI ChatClient + 5-layer CallAdvisor wiring
    └── SpringAiDecideTurn.java        # Prompt assembly, bounded file reads & tool execution

eval/src/main/java/io/diag/eval/
├── cli/
│   ├── MatrixCli.java                 # Command-line application entrypoint
│   ├── MatrixConfig.java              # Immutable config parsing CLI flags and env vars
│   └── MatrixExecutionResult.java     # Value record with score reports and artifact paths
├── model/
│   ├── DiagnosticTriage.java          # Triage findings and recommended recovery actions
│   ├── MatrixScoreReport.java         # Aggregated matrix pass/fail metrics
│   ├── RegressionKey.java             # Grouping tuple (promptHash, model, aggregator)
│   ├── RegressionRow.java             # Statistical summary row for markdown tables
│   └── RunScore.java                  # Per-run accuracy, effectiveness, efficiency score
└── service/
    ├── AgentLoopFactory.java          # Functional loop creator interface
    ├── DiagnosticTriageService.java   # 4-stage failure hierarchy analyzer
    ├── EvalScorer.java                # Ground truth scoring service
    ├── HtmlReportGenerator.java       # Offline HTML report exporter
    ├── MatrixRunner.java              # Multi-target sequential orchestrator
    └── RegressionTableService.java    # Grouped regression table calculator
```

---

## CLI Usage & Getting Started

### Prerequisites
- **JDK 21** or later
- **Maven 3.9+**
- **Docker & Docker Compose** (allocated $\ge 4\text{ CPUs}$, $\ge 6\text{ GB RAM}$)
- **PostgreSQL 17 + `pgvector`** (`pgvector/pgvector:pg17` via provided docker-compose)

### Quick Start

1. **Start the Evidence & Vector Store**:
   ```bash
   docker compose -f docker/evidence/docker-compose.yml -p diag-evidence up -d
   ```

2. **Build the Project**:
   ```bash
   mvn clean install -DskipTests
   ```

3. **Run the Offline Agent-Core & Evaluation Verification Gates**:
   ```bash
   # Verify 5-Layer CallAdvisor Onion, Agent Loop, Keep-Rule v2, and Step 13 Episodic RAG Memory (64 offline unit tests)
   mvn -pl agent-core -am test "-Dsurefire.failIfNoSpecifiedTests=false" "-Dtest=AdvisorOnionTest,SpringAiDecideTurnTest,BoundedReadSourceTest,AgentConfigTest,Step8VerifyGate,Step9VerifyGate,Step10VerifyGate,Step13VerifyGate"

   # Verify Step 12 End-to-End Evaluation Matrix Gate
   mvn test -pl eval -Dtest=Step12VerifyGate
   ```

4. **Execute the Evaluation Matrix via CLI**:
   ```bash
   mvn exec:java -pl eval \
     -Dexec.mainClass="io.diag.eval.cli.MatrixCli" \
     -Dexec.args="--targets=S1,S2,S3,S4 --reports-dir=target/reports --provider=anthropic --model=claude-3-7-sonnet"
   ```

### Command-Line Arguments & Environment Overrides

| CLI Argument | Environment Variable | Default Value | Description |
|---|---|---|---|
| `--targets=S1,S2` | `DIAG_TARGETS` | `S1,S2,S3,S4` | Comma-separated canonical target IDs |
| `--reports-dir=path` | `DIAG_REPORTS_DIR` | `reports` | Directory where HTML and Markdown reports are written |
| `--provider=name` | `DIAG_PROVIDER` | `anthropic` | LLM provider (`anthropic`, `openai`, `ollama`) |
| `--model=name` | `DIAG_MODEL` | `claude-3-7-sonnet` | Model identifier |
| `--dry-run` | `DIAG_DRY_RUN` | `false` | Dry-run mode using mock generative responses |

---

## Next Phase Plan: The 3-Stage AI Engineering Roadmap

The v1.0 & v1.1 milestones proved that autonomous performance diagnosis, 5-layer `CallAdvisor` governance, and `pgvector` episodic RAG memory work reliably end-to-end on frontier closed models. However, true AI engineering depth requires moving beyond merely consuming hosted APIs—it means fine-tuning and training open-weights models on domain telemetry and **proving the improvement through the evaluation harness**.

The project roadmap follows three distinct, empirical stages where every transition is a measurement, not a vibe:

```
┌───────────────────────────────────────────────────────────────────────────────────┐
│                          THE 3-STAGE AI MODELING ROADMAP                          │
├───────────────────────────────────────────────────────────────────────────────────┤
│                                                                                   │
│  Stage 1: Closed Frontier Model + RAG Memory [COMPLETED — Step 13 Verified]       │
│  ├── Baseline: Claude 3.7 Sonnet / OpenAI via Spring AI + 5-Layer Advisor Onion   │
│  └── Capstone: pgvector episodic memory + active @Tool + zero-leakage AST filter  │
│                                      │                                            │
│                                      ▼                                            │
│  Stage 2: Open-Weights Model Swap & Gap Quantification [NEXT MILESTONE]           │
│  ├── Swap in Qwen 2.5 Coder / Llama 3.3 via Ollama / vLLM                         │
│  └── Regression table measures exact accuracy, effectiveness, & cost delta        │
│                                      │                                            │
│                                      ▼ (The measured gap becomes the target)     │
│  Stage 3: Domain Training on Our Own Experiment Data                              │
│  ├── 3a. Deep Learning & Transformer Foundations (Backprop, Attention, KV Cache)  │
│  ├── 3b. LoRA/QLoRA SFT on kept trajectories (Rejection sampling on proven traces)│
│  ├── 3c. DPO Preference Optimization (Keep-Rule v2 acts as grounded reward model) │
│  └── 3d. Small Specialist Routing Classifier (JFR signature -> H1-H7 routing)     │
│                                                                                   │
│  [Proof Artifact]: 1-Page Empirical Scorecard (Trained Open vs Frontier Closed)   │
└───────────────────────────────────────────────────────────────────────────────────┘
```

### Stage 1: Closed Frontier Model + RAG Memory (Completed — Step 13 Verified)
- **Objective**: Establish the gold-standard baseline on frontier models while demonstrating that proprietary experiment data improves diagnostic performance with zero model retraining.
- **Delivered in Step 13 (`io.diag.agent.memory` & `io.diag.agent.advisor`)**:
  - Implemented hybrid vector search (`pgvector` HNSW cosine similarity $\ge 0.65$ + `FilterExpressionBuilder` metadata AST predicates) over historical `KEPT` and `REVERTED` diagnostic episodes.
  - Built `@Tool queryPastFindings` with 6 server-side guards, 3× over-fetch diversity deduplication (`targetId : template`), and the 2×2 Advisory Matrix (`MemoryContextFormatter`).
  - Enforced **Dual-Mode Benchmark Isolation** (`targetId != currentTargetId` in `BENCHMARK_ISOLATED` mode) to mathematically prevent benchmark contamination and train/test leakage.

### Stage 2: Open-Weights Model Swap & Gap Quantification (Next Milestone)
- **Objective**: Run the exact same diagnostic matrix using open-weights models (e.g. Qwen 2.5 Coder 32B/7B, Llama 3.3 70B, Mistral) through local inference (Ollama / vLLM) or OpenAI-compatible endpoints.
- **The Empirical Gap Measurement**:
  - Because `(prompt_hash, model)` is a first-class key in our regression table, the system quantifies the exact zero-shot accuracy, convergence, and dollar-cost delta between closed and open models.
  - Open models are expected to exhibit weaker zero-shot tool reliability and hypothesis precision; **this measured performance gap becomes Stage 3's explicit training target**.

### Stage 3: Training on Our Own Experiment Data (The Modeling-Depth Core)
Rather than relying on generic synthetic data, this project manufactures its own high-quality domain dataset as a natural byproduct of every execution run:

1. **Rejection-Sampled Supervised Fine-Tuning (SFT)**:
   - Kept iterations (`outcome = 'KEPT'`) represent ground-truth-verified, benchmark-proven traces.
   - SFT trains open models on these successful reasoning and fix application trajectories (the STaR paradigm).
2. **Direct Preference Optimization (DPO)**:
   - Kept vs. Reverted iteration pairs form natural, benchmark-grounded preference data:
     $$\text{Preferred: } \tau_{\text{kept}} \succ \text{Dispreferred: } \tau_{\text{reverted}}$$
   - Keep-Rule v2 acts as an objective, physical reward model—requiring zero subjective human labeling.
3. **Specialist JFR Routing Classifier**:
   - A lightweight classical ML model trained on JFR event distribution vectors to route directly to hypothesis categories (`H1`–`H7`), bypassing LLM inference for 80% of routine cases and dramatically slashing operational costs.

### The Ultimate Proof Artifact
A single-page, empirical scorecard comparing the fine-tuned open-weights model against the frontier closed model across the S1–S4 matrix, detailing accuracy, convergence rate, latency reduction, and 10x lower inference cost.

### The Java Wedge Advantage
While most AI engineers build in Python/Node with thin API wrappers, uniting enterprise Java systems reliability (Saga orchestrations, JFR kernel profiling, Docker resource sandboxing, Flyway schema migrations) with deep AI modeling depth (RAG, evaluations, SFT, DPO) establishes an exceptionally rare, defensible engineering profile.
