# Open Questions & Unsettled Assumptions

Everything the design docs cannot settle on paper. Each item says where it
gets settled (build step) and what to do if the answer is bad. Check items
off as runs produce data. Companion to `v1.0-scope.md` rev 2 /
`v1.0-build-steps.md`.

## A. Hypotheses needing hand-measurement (before the LLM ever runs)

1. **S1 fix magnitude** — **SETTLED 2026-09-03** (REF `jar-unpack-exp`
   vs h3 baseline): RPS +47%, p50 −87%, lock rate −81%, but p95 +24% /
   p99 +66% / max +139%. A saddle — correct mechanism, worse tail. It
   falsified the p95-only keep rule (scope §10.5 → keep-rule v2) and made
   S1's ground truth a two-step sequence. Follow-up stays open:
1b. **S1 convergence depth** — PARTIALLY SETTLED 2026-09-03 (REF
   `resource-cache-exp`): unpack + resource caching does NOT converge —
   hypothesis falsified, all deltas noise-level. Honest depth is ≥3
   iterations.
1c. **S1 iteration-3 mechanism** — before spending a benchmark: read
   petclinic's `MvcConfig`/`addResourceHandlers` and pin who registers
   `ResourceUrlEncodingFilter` and which URL paths actually flow through
   `ResourceUrlProvider` (static paths vs per-request page URLs). Then
   choose: neutralize the filter (likely correct; small config-code
   change) vs resolver-cache pre-warm (only works if misses are static
   paths — the flat lock distribution argues they're not). THEN hand-
   measure: does eliminating the call site move p95 below the 2500ms
   baseline? If not, the tail lives elsewhere (h3 showed ThreadPark p95
   1000ms CRITICAL) and the tail's remaining cause is queueing-floor, not
   locks — PASS wording (final-state RPS or p95 beyond noise, §10.5a)
   already covers it; the S1 target vector gets pinned from whatever this
   run measures.
2. **S2 seed shows pool-wait signal on H2** — H2's fast I/O may mask pool
   starvation at 200 VUs. Settled: step 10 sanity check (one JFR on the
   seeded app). Bad answer → strengthen the seed (pool=1, connection lease
   time), not switch to a real DB (scope creep).
3. **S3 seed shows GC CRITICAL** — same check; `-Xmx256m` at this load
   should thrash. Verify, don't assume.
4. **S4 seed shows app-class monitor contention** — the REF practice-mvc
   port must reproduce its intended signal.
5. **S2–S4 expected fixes actually fix** — per-seed fix-validation (step
   10, §10.29). Bad answer → the effectiveness metric would lie; fix the
   fix before the matrix runs.
6. **S4's fix is template-able** — "narrow the lock" may need real code
   edits beyond templates; decides whether S4 stays v1.0 or slides to
   stretch.

## B. Decision checkpoints

7. **Model/provider** (before step 8) — tool-calling reliability is the
   criterion; whichever API key exists. Data that settles it: one manual
   chat turn that calls `runBenchmark` correctly.
8. **Spring AI actual GA + structured-output mechanism** — docs assume
   1.0.x GA `@Tool` + schema-validated decisions; confirm the real API
   shape (structured converters vs. manual Jackson+validation layer).
   Settled: step 8 spike.
9. **Postgres version/port, no local conflict** — step 0 trivial check.

## C. Defaults needing calibration with first real data

10. **Accuracy ≥70% / convergence ≥2-of-4** — arbitrary until the first
    matrix run; recalibrate per scope §6.
11. **Noise-floor formula adequacy** — `max(5%, spread-of-3)` vs.
    Docker-Desktop-on-Windows reality. First baselines (step 4) show the
    spread; if spread regularly >5%, revisit (more baseline runs? stricter
    host hygiene?).
12. **Baseline-cache TTL** — no number chosen; set from observed cross-day
    drift after a few sessions.
13. **Guardrail caps ($5 / 60 min / token cap)** — guesses until real cost
    data lands in trajectory rows.
14. **Tool-call bound ≤10/iteration** — may be too tight if the model
    legitimately reads several files; watch WASTED iterations.
15. **Iteration time budget 4–6 min** — estimate; the first real loop run
    sets the matrix wall-clock expectation (~2 h claim).
16. **Keep-rule v2 bounds** — mechanism-signal drop >50% and p95-regression
    bound ≤50% are defaults derived from ONE data point (the S1 saddle,
    p95 +24%). Recalibrate against the first real loop runs: too loose →
    garbage changes survive; too tight → saddles become unreachable again.
17. **Per-target target vectors** — STRETCH is now a data-derived vector
    per target (scope §6); each vector gets pinned by that target's
    fix-validation run (S1's lands after the iteration-3 measurement).
    Until then, no target has a STRETCH definition.

## D. Environment realities (verify on THIS machine)

16. **Stock baseline reproduces** — step 4 gate: rps ~189 / p95 ~2500 ms
    ballpark on this hardware. Wildly off → envelope problem; fix before
    anything else depends on numbers.
17. **Docker VM headroom** — ≥4 CPUs / 6 GB actually allocated; runs on AC
    power; no heavy host apps during benchmarks.
18. **Capped Postgres stays quiet** — if benchmark noise correlates with DB
    activity despite the 0.5 CPU/512 MB cap → host-install Postgres
    (scope §9 fallback).
19. **k6 container sustains 200 VUs** on 1 CPU/512 MB — implicitly settled
    by #16 reproducing.
20. **JFR overhead ~1–2% and constant** — carried assumption from v0.1;
    constancy matters more than size (comparability). Check across the 3
    baseline repeats.

## E. LLM-side unknowns (only real runs answer)

21. **Is the 2–5 KB JfrReport enough signal** — the core research bet.
    First S1 run: does the model name H5/classloading from the aggregate?
22. **Does the JFR diff actually help** — hypothesis (scope §4.1); A/B via
    the harness later.
23. **System prompt design** — the step 9 sketch is a starting point;
    expect evidence-driven iteration (which failure dominates: bad
    hypothesis, bad template pick, or malformed decisions?).
24. **Ledger advisory value** — unmeasured; experiment post-harness.
25. **Read-back checks pass on honest runs** — k6 `checks rate>0.95` must
    hold on the untampered app or the anti-gaming gate false-positives
    (verify in step 2/4).

## F. Deliberately deferred (decided — don't re-litigate, don't forget)

26. Gated experiments: RAG run-memory (pgvector), self-critique pass,
    multi-agent A/B — only after step 11 can score them.
27. Fine-tuning worth it — phase 2; needs the trajectory data v1.0
    collects.
28. Real-DB target variant (MySQL/Postgres), Kafka/Redis, WebFlux — next
    phase.
29. S5 (N+1 selects) as a permanent matrix member — stretch only.
30. Micrometer time-series scraping during load — deferred (scope §7);
    revisit if JFR aggregates prove insufficient for pool/GC diagnosis.
