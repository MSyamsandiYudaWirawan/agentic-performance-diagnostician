# Agentic Performance Diagnostician

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Spring AI](https://img.shields.io/badge/Spring%20AI-2.0.1-blue.svg)](https://spring.io/projects/spring-ai)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-17-blue.svg)](https://www.postgresql.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Status](https://img.shields.io/badge/Status-v1.0%20Capstone%20Achieved-success.svg)](#roadmap)

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
3. [The Autonomous Agent Loop](#the-autonomous-agent-loop)
4. [Keep-Rule v2 & Saddle Navigation](#keep-rule-v2--saddle-navigation)
5. [Canonical Target Matrix (Ground Truth)](#canonical-target-matrix-ground-truth)
6. [Evaluation Scoring & Diagnostic Triage](#evaluation-scoring--diagnostic-triage)
7. [Reporting Pipeline (Zero Manual Collation)](#reporting-pipeline-zero-manual-collation)
8. [Module Structure](#module-structure)
9. [CLI Usage & Getting Started](#cli-usage--getting-started)
10. [Study & Practice Roadmap](#study--practice-roadmap)

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
├── evidence/        # Postgres persistence, Flyway migrations, schema entities, JFR/Load DTOs
├── target-runner/   # Docker container lifecycle, k6 load driver, JFR extraction, git patch applier
├── agent-core/      # Spring AI orchestration, prompt engineering, decision validation, Keep-Rule v2
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
   │   │  (Spring AI   │     │ (Git patch    │      │  (Docker, smoke,  │   │
   │   │   reasoning)  │     │  & templates) │      │   k6, JFR harvest)│   │
   │   └───────▲───────┘     └───────────────┘      └─────────┬─────────┘   │
   │           │                                              │             │
   │           └─────────── [JFR Diff & Load Data] ───────────┘             │
   │                                                                        │
   │                         ┌───────────────┐                              │
   │                         │  KeepRule v2  │                              │
   │                         │ (Judge delta) │                              │
   │                         └───────┬───────┘                              │
   └─────────────────────────────────┼──────────────────────────────────────┘
                                     │ persists all runs & traces
                                     ▼
                      ┌────────────────────────────────────────┐
                      │            EvidenceService             │
                      │    (Postgres 17 + Flyway Schema)       │
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

## The Autonomous Agent Loop

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
     Strengthen Hypothesis               Weaken Hypothesis
```

### Safety Guardrails & Resiliency
- **Ceilings**: Maximum dollar spend (e.g. $\$10.00$), token limit ($500{,}000$), wall-clock limit ($30\text{ min}$), and maximum iterations ($5$).
- **Partial Evidence Retention**: If a run hits an iteration ceiling or cost threshold, it transitions to `ABORTED` without discarding prior telemetry.
- **Checkpoint Resume**: Interrupted runs can be resumed (`AgentLoop.resume(runId)`). The loop detects existing baseline records from the database (avoiding redundant $3\times$ benchmarks), executes a `git reset --hard` to `lastKeptSha` to ensure clean working tree hygiene, and continues from iteration $n$.

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
eval/src/main/java/io/diag/eval/
├── cli/
│   ├── MatrixCli.java              # Command-line application entrypoint
│   ├── MatrixConfig.java           # Immutable config parsing CLI flags and env vars
│   └── MatrixExecutionResult.java   # Value record with score reports and artifact paths
├── model/
│   ├── DiagnosticTriage.java        # Triage findings and recommended recovery actions
│   ├── MatrixScoreReport.java       # Aggregated matrix pass/fail metrics
│   ├── RegressionKey.java           # Grouping tuple (promptHash, model, aggregator)
│   ├── RegressionRow.java           # Statistical summary row for markdown tables
│   └── RunScore.java                # Per-run accuracy, effectiveness, efficiency score
└── service/
    ├── AgentLoopFactory.java        # Functional loop creator interface
    ├── DiagnosticTriageService.java # 4-stage failure hierarchy analyzer
    ├── EvalScorer.java              # Ground truth scoring service
    ├── HtmlReportGenerator.java     # Offline HTML report exporter
    ├── MatrixRunner.java            # Multi-target sequential orchestrator
    └── RegressionTableService.java  # Grouped regression table calculator
```

---

## CLI Usage & Getting Started

### Prerequisites
- **JDK 21** or later
- **Maven 3.9+**
- **Docker & Docker Compose** (allocated $\ge 4\text{ CPUs}$, $\ge 6\text{ GB RAM}$)
- **PostgreSQL 17** (or run via provided docker-compose)

### Quick Start

1. **Start the Evidence Store**:
   ```bash
   docker compose -f docker/evidence/docker-compose.yml -p diag-evidence up -d
   ```

2. **Build the Project**:
   ```bash
   mvn clean install -DskipTests
   ```

3. **Run the Master Verification Gate**:
   ```bash
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

## Study & Practice Roadmap

This codebase serves as a reference platform for exploring agentic systems and performance engineering. The ongoing research roadmap includes:

- [x] **v1.0 Capstone**: Autonomous single-agent loop, JFR extraction, Keep-Rule v2, S1–S4 canonical matrix, automated scoring, offline reports.
- [ ] **Spring AI Mastery & Core Refactoring**: In-depth hands-on exploration of Spring AI function calling, observation registries, and custom tool adapters.
- [ ] **Run Memory with Vector Search**: Storing past `ExperimentRecord` embeddings in Postgres (`pgvector`) to enable the agent to retrieve relevant historical fixes.
- [ ] **Self-Critique Reflection Gate**: Adding a secondary reflection turn before code changes are applied to validate syntax and identify edge-case risks.
- [ ] **Multi-Agent Specialization**: Splitting the single loop into specialized cooperating agents (Diagnostician $\rightarrow$ Fixer $\rightarrow$ Benchmark Evaluator).
- [ ] **Open-Weights Model Evaluation**: Running the full matrix against local open models (e.g. Qwen 2.5 Coder, Llama 3.3 via Ollama) and quantifying the accuracy/cost tradeoff against frontier models.
- [ ] **Trajectory Fine-Tuning**: Leveraging kept trajectory logs to build high-quality SFT (Supervised Fine-Tuning) and DPO (Direct Preference Optimization) datasets for domain-specific fine-tuning.
