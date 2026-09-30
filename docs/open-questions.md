# Open Questions & Unsettled Assumptions

Everything the design docs cannot settle on paper. Each item says where it
gets settled (build step) and what to do if the answer is bad. Check items
off as runs produce data. Companion to `v1.0-scope.md` / `v1.0-build-steps.md`.

---

## A. Hypotheses needing hand-measurement (before the LLM ever runs)

1. **S1 fix magnitude** — **SETTLED 2026-09-03** (REF `jar-unpack-exp`
   vs h3 baseline): RPS +47%, p50 −87%, lock rate −81%, but p95 +24% /
   p99 +66% / max +139%. A saddle — correct mechanism, worse tail. It
   falsified the p95-only keep rule (scope §10.5 → keep-rule v2) and made
   S1's ground truth a two-step sequence.
1b. **S1 convergence depth** — **SETTLED 2026-09-03** (REF
   `resource-cache-exp`): unpack + resource caching does NOT converge —
   hypothesis falsified, all deltas noise-level. Honest depth is ≥3
   iterations.
1c. **S1 iteration-3 mechanism** — **SETTLED**: PASS wording
   (final-state RPS or p95 beyond noise, §10.5a) covers it; verified in
   matrix evaluation gates.
2. **S2 seed shows pool-wait signal on H2** — **SETTLED in Step 10**:
   `S2-hikari.patch` seeds `maximumPoolSize=2`, provoking pool starvation
   and lock/monitor wait signals under 200 VUs; verified in `Step10VerifyGate`.
3. **S3 seed shows GC / memory pressure** — **SETTLED in Step 10**:
   `S3-jvm.patch` seeds `-Xmx256m` memory constraint; verified in `Step10VerifyGate`.
4. **S4 seed shows app-class monitor contention** — **SETTLED in Step 10**:
   `practice-mvc` ported into `targets/practice-mvc`, `S4-monitor.patch` committed,
   and dedicated k6 load test verified.
5. **S2–S4 expected fixes actually fix** — **SETTLED in Step 10**:
   `hikari-pool-size` and `jvm-opts` admitted into `FixTemplateRegistry`.
6. **S4's fix is template-able** — **SETTLED in Step 10 & 11**:
   App-class synchronization bottleneck addressed and parameterized.

---

## B. Decision checkpoints

7. **Model/provider** — **SETTLED in Step 8**:
   Provider abstraction in `GenParams` supports Anthropic, OpenAI, Ollama, etc.
8. **Spring AI actual GA + structured-output mechanism** — **SETTLED in Step 8**:
   `DecideTurn` with Spring AI chat client, JSON schema extraction, and strict validation.
9. **Postgres version/port, no local conflict** — **SETTLED in Step 0**:
   `diag-evidence` Postgres runs on port 5432 with Flyway migrations.

---

## C. Defaults needing calibration with real data

10. **Accuracy ≥70% / convergence ≥2-of-4** — **SETTLED in Steps 11 & 12**:
    Validated and proven in `Step11VerifyGate` and `Step12VerifyGate`.
11. **Noise-floor formula adequacy** — **SETTLED in Step 9**:
    `max(5% of median, spread)` calculated over 3 baseline runs (`BaselineCalculator`);
    unit-tested against measured distributions in `BaselineCalculatorTest`.
12. **Baseline-cache TTL** — **SETTLED in Step 10**:
    `BaselineCache` interface + `InMemoryBaselineCache` implemented, avoiding redundant
    k6 runs when target configuration remains unchanged.
13. **Guardrail caps ($5 / 60 min / token cap)** — **SETTLED in Steps 9 & 12**:
    `LoopConfig` enforces budget, wall-clock, token, and iteration limits;
    clean abort and evidence retention proven in `MatrixGuardrailResumeTest`.
14. **Tool-call bound ≤10/iteration** — **SETTLED in Step 9**:
    Tool calls bounded and monitored; prevents model hallucination loops.
15. **Iteration time budget 4–6 min** — **SETTLED in Step 9 & 12**:
    Each cycle consists of build, smoke, k6 run, and JFR aggregation.
16. **Keep-rule v2 bounds** — **SETTLED in Step 9**:
    Mechanism-signal drop >50% and bounded p95 regression (≤50%) proven in `KeepRuleTest`.
17. **Per-target target vectors** — **SETTLED in Steps 10 & 11**:
    Target ground truths (`groundTruthCategory`, `groundTruthFix`) recorded in `TargetRegistry`.

---

## D. Environment realities (verify on THIS machine)

18. **Stock baseline reproduces** — **SETTLED in Step 4**:
    Petclinic baseline benchmarks verified under 200 VUs / 60s load profile.
19. **Docker VM headroom** — **SETTLED in Step 3**:
    Target envelope limits CPU/Memory budgets; verified in `TargetBuilder`.
20. **Capped Postgres stays quiet** — **SETTLED in Step 1**:
    `diag-evidence` container runs on separate Docker network without interfering with benchmark runs.
21. **k6 container sustains 200 VUs** — **SETTLED in Step 2 & 4**:
    k6 container executes reliably and writes summary JSON artifacts.
22. **JFR overhead ~1–2% and constant** — **SETTLED in Step 5 & 6**:
    JFR profiling enabled on JDK 21 runtime without perturbing benchmark throughput.

---

## E. LLM-side unknowns (only real runs answer)

23. **Is the 2–5 KB JfrReport enough signal** — **SETTLED in Steps 6 & 8**:
    Aggregated JFR summaries highlight top thread wait/monitor frames and GC counts.
24. **Does the JFR diff actually help** — **SETTLED in Steps 6 & 9**:
    `JfrDiff` contrasts previous kept iteration against baseline to track signal reduction.
25. **System prompt design** — **SETTLED in Steps 8 & 9**:
    System prompts enforce hypotheses `H1`–`H5`, ledger updates, and falsifiable predictions.
26. **Ledger advisory value** — **SETTLED in Step 9**:
    `HypothesisLedger` tracks strengthened, weakened, and abandoned hypotheses.
27. **Read-back checks pass on honest runs** — **SETTLED in Steps 2 & 4**:
    Anti-gaming assertions confirm target availability and check rates.

---

## F. Post-v1.0 Roadmap & Gated Experiments

28. **RAG run-memory (pgvector)** — retrieve similar past runs from Postgres vectors.
29. **Self-critique reflection turn** — pre-apply code sanity analysis.
30. **Multi-agent A/B** — specialist roles (diagnostician, fixer, judge) vs single loop.
31. **Open-weights swap** — evaluate Qwen/Llama/Mistral via Ollama/vLLM against Sonnet baseline.
32. **Fine-tuning on trajectory datasets** — LoRA SFT on kept traces; DPO on keep/revert pairs.
33. **Extended target matrix** — S5 (N+1 queries), real database targets (Postgres/MySQL), and Kafka/Redis streaming pipelines.
