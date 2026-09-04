# Evidence Store Schema

Postgres schema owned by Flyway (`V1__init.sql`). All 6 tables live outside
the measured envelope — own compose project `diag-evidence`, never torn down
per benchmark run (scope §5, §10.16). No foreign key constraints; integrity
is enforced at the application layer (`EvidenceStore`).

---

## Table relationships (logical, no FK constraints)

```
target  (one row per slow app in the matrix)
  └── run  (one agent loop execution against one target)
        ├── load_report      (one k6 benchmark result — baselines + per-iteration)
        ├── jfr_report       (one JFR profiling analysis — baselines + per-iteration)
        ├── iteration        (one pass: JFR → LLM decide → apply → benchmark → keep/revert)
        │     ├── load_report_id  (benchmark taken AFTER the change)
        │     └── jfr_report_id   (JFR taken BEFORE the change — what the LLM saw)
        └── trajectory_event (every LLM call + tool call in order, with cost)
```

---

## `target`

One row per app the agent diagnoses. Seeded once, never mutated during runs.

| Column | Type | Description |
|---|---|---|
| `id` | varchar(40) PK | Short identifier — `S1`, `S2`, `S3`, `S4` |
| `name` | varchar(256) | Human label — e.g. `stock-petclinic`, `petclinic-pool-starved` |
| `base_repo` | varchar(500) | Path to the git clone — e.g. `targets/petclinic` |
| `seed_patch` | text | Committed patch that degrades the app (S2/S3/S4). Null for S1 (stock). e.g. `hikari maximumPoolSize=2` for S2 |
| `baseline_sha` | varchar(40) | Git commit the agent always reverts to between runs. `revertTo()` resets to this sha |
| `ground_truth_category` | varchar(10) | Known bottleneck category — `H1`–`H7` (from `jfr-diagnose.sh` hypothesis menu). The answer the agent is scored against |
| `ground_truth_fix` | text | Known fix — e.g. `jar-unpack`, `hikari-pool-size`, `jvm-opts`. Used by the eval harness to score effectiveness |
| `profile_hash` | varchar(64) | SHA of the k6 script. Part of the baseline cache key — if the script changes, cached baselines are invalidated (scope §10.24) |

---

## `run`

One agent loop execution against one target. Central table — everything else
references `run.id`. The regression table groups runs by
`(prompt_hash, model, aggregator_version)` to answer "did my change help?"

| Column | Type | Description |
|---|---|---|
| `id` | varchar(30) PK | `yyyyMMdd-HHmmss-<8char>` — minted once per run. Also used as the compose project name `-p <run-id>` |
| `target_id` | varchar(40) | Which target this run is diagnosing |
| `status` | varchar(12) | State machine: `RUNNING` → `COMPLETED` \| `INCOMPLETE` \| `ABORTED`. `RUNNING` row is the single-flight lock — a second run refuses to start while one exists (scope §10.27) |
| `started_at` | timestamptz | When the run started |
| `finished_at` | timestamptz | When the run ended. Null while `RUNNING` |
| `provider` | varchar(100) | LLM provider — e.g. `anthropic`, `openai` |
| `model` | varchar(100) | Model id — e.g. `claude-3-5-sonnet-20241022` |
| `prompt_hash` | varchar(64) | SHA of the system prompt text. Regression table key — changing the prompt produces a new group |
| `aggregator_version` | varchar(20) | Version of `JfrAnalyzer`. Old runs stay comparable under their own version even if thresholds change |
| `gen_params` | jsonb | Generation parameters — temperature, max_tokens, etc. Full attribution beyond prompt_hash/model (scope §10.34) |
| `tokens_in` | bigint | Total input tokens for the run (sum of `trajectory_event.tokens_in`) |
| `tokens_out` | bigint | Total output tokens for the run |
| `cost_usd` | numeric(10,6) | Total spend for the run. Guardrail cap checked against this (scope §10.25) |
| `baseline_p95_ms` | numeric(10,2) | Cached baseline p95 this run measured against. Best-of-3 baselines, cached by `(target_id, profile_hash)` (scope §10.24) |
| `noise_floor_ms` | numeric(10,2) | `max(5%, spread-of-3-baselines)`. A change must beat this to count as an improvement (scope §10.5) |

---

## `load_report`

One k6 benchmark result. A run produces several: 3 baselines + one per
iteration. Baselines are not iterations — they are stored here independently.

| Column | Type | Description |
|---|---|---|
| `id` | bigserial PK | Auto-increment surrogate |
| `run_id` | varchar(30) | Which run this benchmark belongs to |
| `label` | varchar(30) | Where in the loop: `baseline-1`, `baseline-2`, `baseline-3`, `iter-1`…`iter-5`, `smoke-1`…`smoke-n`. Smoke results are never used as benchmark recordings (scope §10.15) |
| `payload` | jsonb | Full `LoadReport` JSON — `rps`, `p50/p95/p99/max`, `failRate`, `checkPassRate`, `thresholds.verdict`, `thresholds.breached[]`. Stored verbatim — same JSON the Java record serializes to |
| `k6_summary_path` | text | Path on disk to the raw k6 summary JSON. DB stores the path, never the binary (scope §10.18) |

---

## `jfr_report`

One JFR profiling analysis result. Produced for every benchmark run alongside
the `load_report`. The `payload` is the 2–5 KB aggregated analysis the LLM
actually reads — not the raw `.jfr` file.

| Column | Type | Description |
|---|---|---|
| `id` | bigserial PK | Auto-increment surrogate |
| `run_id` | varchar(30) | Which run this recording belongs to |
| `label` | varchar(30) | Same label as the corresponding `load_report` |
| `payload` | jsonb | Full `JfrReport` JSON — per-signal `{count, p50/p95/max, severity, topFrames}` for all 7 event types (`JavaMonitorEnter`, `ThreadPark`, `ExecutionSample`, `SocketRead/Write`, `GCPhasePause`, `ObjectAllocationSample`, `ExceptionThrow`). Includes the diff vs the previous recording in this run (~0.5 KB) |
| `jfr_path` | text | Path on disk to the raw `.jfr` file |
| `jfr_sha256` | varchar(64) | SHA-256 of the `.jfr` file. Proves the artifact was not tampered with; detects if the same recording was analyzed twice (scope §10.18) |

---

## `iteration`

One pass through the agent loop: JFR → LLM decides → apply change →
rebuild + smoke → benchmark → keep or revert.

| Column | Type | Description |
|---|---|---|
| `id` | bigserial PK | Auto-increment surrogate |
| `run_id` | varchar(30) | Which run this iteration belongs to |
| `n` | int | Iteration number within the run — 1 to 5 |
| `hypothesis` | jsonb | What the LLM diagnosed: `{category: "H5", confidence: 0.8, rationale: "..."}` |
| `ledger` | jsonb | Per-category belief state after this iteration: `{H1: 0, H2: -1, H5: 2, ...}`. Strengthened/weakened by the LLM each round. Advisory only in v1.0 — never gates an apply (scope §10.22) |
| `change` | jsonb | What the LLM proposed: `{kind: "template", template: "jar-unpack"}` or `{kind: "edits", edits: [{path, content}, ...]}` |
| `outcome` | varchar(12) | `KEPT` — change improved things, stays in the tree. `REVERTED` — did not improve, `git reset --hard`. `WASTED` — malformed decision or build/smoke failed. `INCOMPLETE` — run was killed mid-iteration |
| `tree_sha` | varchar(40) | Git commit sha after this iteration's change was applied. If `KEPT`, becomes the new base for the next iteration. If `REVERTED`, loop goes back to the previous sha |
| `load_report_id` | bigint | ID of the `load_report` taken **after** this change was applied |
| `jfr_report_id` | bigint | ID of the `jfr_report` taken **before** this change — what the LLM saw when it made its decision |
| `files_touched` | jsonb | `[{path, linesBefore, linesAfter}, ...]`. Anti-gaming audit trail — eval flags any iteration touching files outside `{src/, Dockerfile.target, application*.properties, compose overrides}` (scope §10.28) |
| `keep_type` | varchar(12) | How the change was kept: `RPS` (throughput improved beyond noise floor), `P95` (latency improved beyond noise floor), `MECHANISM` (verified-mechanism keep — the S1 saddle path: p95 got worse but the named lock signal dropped >50%, scope §10.5b) |
| `finding` | text | One sentence written post-hoc: what the evidence actually showed. e.g. `"UrlJarFiles$Cache 71k→0 events, lock rate −81%, but p95 +24%"`. This is the RAG memory dataset for future runs (scope §4.4) |
| `created_at` | timestamptz | Row creation time, defaults to `now()` |

---

## `trajectory_event`

Every single LLM call and tool call in order, with token counts and cost.
This is the phase-2 fine-tuning dataset — queryable from day one.

| Column | Type | Description |
|---|---|---|
| `id` | bigserial PK | Auto-increment surrogate |
| `run_id` | varchar(30) | Which run this event belongs to |
| `ts` | timestamptz | Event timestamp, defaults to `now()` |
| `kind` | varchar(20) | `LLM_REQ` — message sent to the model. `LLM_RESP` — model reply. `TOOL_CALL` — model invoked a tool (`runBenchmark`, `readSource`, `applyChange`, `captureAndAnalyzeJfr`). `TOOL_RESULT` — what the tool returned back to the model |
| `payload` | jsonb | Full message / response / tool args / tool result as JSON |
| `tokens_in` | int | Input tokens for this single event |
| `tokens_out` | int | Output tokens for this single event |
| `cost_usd` | numeric(10,6) | Cost for this single event. Sum across a run = `run.cost_usd`. Per-event granularity lets you see cost trajectory and identify expensive tool calls |
