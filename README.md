# Agentic Performance Diagnostician

An AI agent that takes a slow Java/Spring service, diagnoses the performance
bottleneck, applies one fix per iteration, and proves improvement with
benchmarks — autonomously:

```
benchmark baseline -> capture JFR under load -> aggregate -> hypothesis
-> apply one change -> rebuild -> re-benchmark -> keep or revert -> iterate
```

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

Every run's evidence (load reports, JFR aggregates, tool calls, token/cost
trajectory) lands in a Postgres evidence store outside the measured envelope,
and every run is auto-scored against seeded targets with known ground truth.
The decision rule is evidence-gated: the LLM makes falsifiable predictions,
the pipeline verifies them (keep-rule v2 — RPS or p95 classic keeps,
verified-mechanism keeps for saddle traversal).

Source of truth: `docs/v1.0-scope.md` (locked design), `docs/v1.0-build-steps.md`
(verify-gated build order), `docs/open-questions.md` (what only runs can settle).

---

## Strategy

Three stages, each transition measured by the eval harness — not vibes:

1. **Stage 1 — Closed model + RAG** (current): frontier API model (Claude/GPT-4)
   via Spring AI. Capstone = RAG over past `ExperimentRecord`s proves
   "our data improved the agent" with zero training.
2. **Stage 2 — Open model swap**: same loop, open-weights model (Qwen/Llama/Mistral).
   The regression table shows the accuracy/cost delta on our task. That gap
   becomes Stage 3's target.
3. **Stage 3 — Train on our data**: LoRA SFT on kept trajectories, DPO on
   keep/revert pairs. The project manufactures the dataset as a byproduct —
   every `trajectory_event` row collected from day one.

---

## Roadmap

### Phase 1 — Core pipeline (closed AI, current)

Build the deterministic pipeline first; no LLM until step 8 so every piece
is independently testable.

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
| 7 | `applyChange` + revert + fix templates (`jar-unpack` admitted; others gated) | 🔄 in progress |
| 8 | Spring AI tool wiring + structured decision schema (validated before apply) | ⬜ |
| 9 | Agent loop — ledger, keep-rule v2, guardrails, resume; fake-LLM dry-run first | ⬜ |
| 10 | Target registry + seeds S2–S4 + per-seed fix-validation | ⬜ |
| 11 | Eval harness + HTML report — accuracy/effectiveness/efficiency scored from DB | ⬜ |
| 12 | Full matrix run (S1–S4) against v1.0 success criterion | ⬜ |

Phase 1 done = matrix runs autonomously, ≥70% diagnosis accuracy, ≥2/4 targets
converge, reports generated with no manual collation, guardrails + resume proven.
Status mirrors the STATUS blocks in `docs/v1.0-build-steps.md` — update both
when a gate goes green.

### Phase 2 — RAG memory + self-critique (closed AI, gated experiments)

Only after step 11 can score them:

- RAG over past `ExperimentRecord`s (pgvector) — does run-memory improve accuracy?
- Self-critique pass before `applyChange` — does a second LLM review reduce WASTED iterations?
- Multi-agent A/B (diagnostician / fixer / judge) vs. the single loop.

Each experiment is a regression-table entry, not a vibe. This is Stage 1's
capstone: first "our data improved the agent" result, zero training.

### Phase 3 — Open model swap (Stage 2)

Gate: v1.0 matrix runs autonomously + Phase 2 capstone done.

- Swap the closed provider for an open-weights model via an OpenAI-compatible
  inference API (Together / Groq / DeepInfra). Spring AI treats provider as
  config — the work is measurement, not integration.
- The `(prompt_hash, model)` regression-table key already exists. Closed vs open
  on accuracy / effectiveness / efficiency across the matrix.
- Exit criterion: harness data naming where the open model stands on our task —
  the baseline the fine-tune must beat.

### Phase 4 — Endgame: fine-tuning on our own data (Stage 3)

Gate: `trajectory_event` rows exist (step 9+), Stages 1–2 done, a measured gap
worth closing.

The ladder, each rung an experiment through the harness:

1. **LoRA/QLoRA SFT** on kept trajectories — rejection-sampling fine-tuning
   (kept iterations are benchmark-verified correct traces).
2. **DPO** on kept-vs-reverted preference pairs — the keep/revert gate is a
   benchmark-grounded reward model; no human labels needed.
3. **Small specialist classifier** — `JfrReport` + ground-truth category →
   routing classifier (cheap, may handle 80% of cases and cut LLM cost).

Goal: match or beat the closed-model accuracy at a fraction of the per-run cost,
with a model you own. The proof artifact: open fine-tuned model vs closed frontier
model on the matrix, through the eval harness, with cost.

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
The only things inside the measured envelope (per-run compose `-p <run-id>`) are
the target container and the k6 container. The evidence Postgres runs in its own
always-on project (`diag-evidence`) and is never torn down per benchmark.

## Target matrix (ground truth)

| id | seed | bottleneck | expected fix |
|---|---|---|---|
| S1 | stock petclinic | H5 lock/classloading — fat-jar classloader lock, 71k `JavaMonitorEnter` events | jar unpack (measured saddle: RPS +47%, p50 −87%, p95 +24%) then eliminate the `ResourceUrlEncodingFilter` call site (candidate, A1c) |
| S2 | `hikari.maximumPoolSize=2` | H2 pool starvation under 200 VUs | pool sizing |
| S3 | `-Xmx256m` JVM seed | H3 GC heap starvation | JVM memory opts |
| S4 | practice-mvc port | H5 lock — synchronized hotspot on app monitor | narrow lock / concurrent structure |

## Study schedule (interleaved with the build)

| Weeks | Build steps | Study focus |
|---|---|---|
| 1–2 | 2–4 (k6, runner, benchmark) | Tokenization + sampling (Karpathy *Deep Dive*); Little's Law; skim *Lost in the Middle* |
| 3–4 | 5–7 (JFR, applyChange — no LLM) | Bandits + small-n stats + saddle intuition; hardens keep-rule thinking exactly when you write it |
| 5–6 | 8–9 (Spring AI tools, agent loop) | ReAct + *Building Effective Agents*; tool-calling docs; falsifiable-prediction design |
| 7–8 | 10–11 (seeds, eval harness) | Eval method + experiment-record discipline; RAG subset when the memory experiment starts |

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
