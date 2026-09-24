# Agentic Performance Diagnostician

An AI agent that takes a slow Java/Spring service, diagnoses the performance
bottleneck, applies one fix per iteration, and proves improvement with
benchmarks — autonomously:

```
benchmark baseline -> capture JFR under load -> aggregate -> hypothesis
-> apply one change -> rebuild -> re-benchmark -> keep or revert -> iterate
```

**Status:** steps 0–8 closed and verify-gated · step 9 (the agent loop) in
progress — keep-rule, noise floors, and baseline math committed, 14 tests
against measured ground truth.

**Design principles:**

- **AI proposes, pipeline disposes.** No path from an LLM decision to
  "improved" skips measurement — every change is committed, rebuilt,
  smoke-gated, and re-benchmarked before keep/revert.
- **The agent arrives last.** The deterministic core (benchmarks, JFR
  aggregation, evidence store) is built and verify-gated before the first
  LLM call — the model inherits an instrument, not a sandbox.
- **The agent cannot edit its own ruler.** Load scripts and resource limits
  live outside the target tree; read-back checks and post-run envelope
  assertions turn manufactured "improvements" into gate failures.
- **Ground truth or it didn't happen.** The JFR aggregator is validated on
  three reference recordings with two opposite known signatures (fat-jar
  lock contention vs. unpacked-jar shift, plus a falsification reference);
  the keep/revert rule is unit-tested against measured experiments.

Every run's evidence (load reports, JFR aggregates, tool calls, token/cost
trajectory) lands in a Postgres evidence store outside the measured envelope,
and every run is auto-scored against seeded targets with known ground truth.

Source of truth: `docs/v1.0-scope.md` (locked design),
`docs/v1.0-build-steps.md` (verify-gated build order),
`docs/step9-agent-loop-spec.md` (the loop, written against the real code),
`docs/open-questions.md` (what only runs can settle).

---

## The agent loop (step 9)

One iteration is a state machine; every failure mode has a named outcome.
The LLM's only lever is `readSource` plus one schema-validated decision JSON —
apply, rebuild, smoke, and measurement are pipeline-owned:

```
                 ┌────────────────────────── guardrail check ───────────────┐
                 ▼                                                          │
 ┌───────┐   ┌────────┐   ┌───────┐   ┌────────┐   ┌─────────┐   ┌───────┐  │
 │DECIDE │→  │ APPLY  │→  │REBUILD│→  │ SMOKE  │→  │ MEASURE │→  │ JUDGE │──┘
 └───┬───┘   └───┬────┘   └───┬───┘   └───┬────┘   └────┬────┘   └───┬───┘
     │           │            │           │             │            │ 
  invalid ×2   rejection    build/test   NOT_TESTABLE  infra failure keep → KEPT
  no-op edit                fail         fail          (k6, docker)  else → REVERTED
     │            │            │           │             │
     └────────────┴──────┬─────┴───────────┴─────────────┘
                         ▼
              WASTED or REVERTED row, next iteration
```

Run-level outcomes: infra failure → `INCOMPLETE` · guardrail cap
($ / wall-clock / tokens / iterations) → `ABORTED` — either way, any
applied-but-unbenchmarked change is reverted first. A killed run resumes
from its last kept sha.

**Keep-rule v2** — the decision rule that separates signal from noise:

- **Classic keep:** RPS *or* p95 improves beyond its noise floor (failRate
  not worse). Floors come from three baseline runs of the unchanged app:
  `max(5% of median, spread)` per metric.
- **Verified-mechanism keep:** the decision's falsifiable prediction
  confirmed — the named JFR signal drops >50%, ≥1 metric improves beyond
  noise, tail regression bounded. This is what lets the loop *keep* the
  jar-unpack saddle (RPS +47%, p50 −87%, p95 **+24%**) that a p95-greedy
  rule would revert and then oscillate on forever.

One benchmark per iteration — its JFR recording doubles as the next
iteration's diagnosis input, diffed against the last **kept** recording.

---

## Strategy

Three stages, each transition measured by the eval harness — not vibes:

1. **Closed model + RAG** *(current)* — frontier API model via Spring AI
   tool calling (provider is config). Capstone: RAG over past experiment
   records proves "our data improved the agent" with zero training.
2. **Open-weights swap** — same loop, open model (Qwen/Llama/Mistral).
   The regression table names the accuracy/cost gap on our task; that gap
   becomes stage 3's target.
3. **Train on our data** — LoRA SFT on kept trajectories (benchmark-verified
   correct traces), DPO on keep/revert preference pairs (the keep gate is a
   benchmark-grounded reward model — no human labels). The project
   manufactures the dataset as a byproduct: every `trajectory_event` row
   collected from day one.

---

## Roadmap

### Phase 1 — deterministic core + single-agent loop (current)

No LLM until step 8 — every piece independently testable first.

| Step | What | Status |
|---|---|---|
| 0 | Repo skeleton, petclinic clone, evidence Postgres, Docker envelope | ✅ done |
| 1 | Evidence DB — Flyway schema, `EvidenceStore`, `LoadReport`/`JfrReport` contracts | ✅ done |
| 2 | k6 script for petclinic (committed artifact, read-back checks) | ✅ done |
| 3 | Target runner — build + Docker lifecycle (`TargetBuilder`, `TargetStack`) | ✅ done |
| 4 | `runBenchmark()` — smoke gate, full run, report parsed + persisted | ✅ done |
| 4b | Manual fix-validation gate — measure jar-unpack saddle, calibrate §6 tiers | ✅ done (REF `jar-unpack-exp`) |
| 5 | JFR capture — always-on, per-run subdir, artifact + sha256 in DB | ✅ done |
| 6 | JFR aggregation + diff — pure Java, validated against 3 REF recordings | ✅ done |
| 7 | `applyChange` + revert + fix templates (`jar-unpack` admitted; others gated) | ✅ done |
| 8 | Spring AI tool wiring + structured decision schema (validated before apply) | ✅ done |
| 9 | Agent loop — ledger, keep-rule v2, guardrails, resume; fake-LLM dry-run first | 🔄 M0 done (keep-rule, floors, baseline) |
| 10 | Target registry + seeds S2–S4 + per-seed fix-validation | ⬜ |
| 11 | Eval harness + HTML report — accuracy/effectiveness/efficiency scored from DB | ⬜ |
| 12 | Full matrix run (S1–S4) against v1.0 success criterion | ⬜ |

Phase 1 done = matrix runs autonomously, ≥70% diagnosis accuracy, ≥2/4
targets converge, reports generated with no manual collation, guardrails +
resume proven. Status mirrors the STATUS blocks in `docs/v1.0-build-steps.md`
— update both when a gate goes green.

**After phase 1** (each gated on the harness being able to score it):
run-memory experiments (RAG via pgvector, self-critique pass, multi-agent
A/B — each a regression-table entry, not a vibe) → open-weights swap
(closed vs. open on the matrix, the measured baseline for the fine-tune) →
fine-tuning (LoRA → DPO → small routing classifier; closed-model accuracy at
a fraction of the per-run cost, with a model you own).

---

## Architecture

Four Maven modules, single repo:

```
evidence/        Flyway schema + Postgres store (Spring Data JDBC) + artifact store + run state machine
target-runner/   build -> compose -> smoke -> k6 -> JFR harvest; seeding
agent-core/      Spring AI loop, tools, prompt, ledger, keep/revert, guardrails
eval/            matrix runner, scoring, regression table, HTML report
```

Dependency graph: `evidence` ← `target-runner` ← `agent-core`; `eval` → all.
The only things inside the measured envelope (per-run compose `-p <run-id>`)
are the target container and the k6 container. The evidence Postgres runs in
its own always-on project (`diag-evidence`) and is never torn down per
benchmark.

## Target matrix (ground truth)

| id | seed | bottleneck | expected fix |
|---|---|---|---|
| S1 | stock petclinic | H5 lock/classloading — fat-jar classloader lock, 71k `JavaMonitorEnter` events | jar unpack (measured saddle: RPS +47%, p50 −87%, p95 +24%) then eliminate the `ResourceUrlEncodingFilter` call site (candidate, A1c) |
| S2 | `hikari.maximumPoolSize=2` | H2 pool starvation under 200 VUs | pool sizing |
| S3 | `-Xmx256m` JVM seed | H3 GC heap starvation | JVM memory opts |
| S4 | practice-mvc port | H5 lock — synchronized hotspot on app monitor | narrow lock / concurrent structure |

## Study focus (interleaved with the build)

| Build steps | Study focus |
|---|---|
| 2–4 (k6, runner, benchmark) | Tokenization + sampling (Karpathy *Deep Dive*); Little's Law; skim *Lost in the Middle* |
| 5–7 (JFR, applyChange — no LLM) | Bandits + small-n stats + saddle intuition; hardens keep-rule thinking exactly when you write it |
| 8–9 (Spring AI tools, agent loop) | ReAct + *Building Effective Agents*; tool-calling docs; falsifiable-prediction design |
| 10–11 (seeds, eval harness) | Eval method + experiment-record discipline; RAG subset when the memory experiment starts |

## Quick start

Prerequisites: JDK 21, Maven, Docker Desktop on the WSL2 backend (≥4 CPUs /
6 GB for the VM), port 5432 free.

```bash
mvn -q package
docker compose -f docker/evidence/docker-compose.yml -p diag-evidence up -d
git clone https://github.com/spring-projects/spring-petclinic targets/spring-petclinic
git -C targets/spring-petclinic config core.autocrlf false
```

Then follow `docs/v1.0-build-steps.md` from Step 0 onward — including Step 0's
packaging bullets: the clone needs `Dockerfile.target` + `compose-service.yml`
committed as its `baselineSha`. The clone is gitignored, so those files exist
only in the local tree — a fresh clone does not have them.
