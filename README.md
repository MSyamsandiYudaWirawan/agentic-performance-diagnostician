# Agentic Performance Diagnostician

An AI agent that takes a slow Java/Spring service, diagnoses the performance
bottleneck, applies one fix per iteration, and proves improvement with
benchmarks — autonomously: `benchmark baseline -> capture JFR under load ->
aggregate -> hypothesis -> apply one change -> rebuild -> re-benchmark ->
keep or revert`. Every run's evidence (load reports, JFR aggregates, tool
calls, token/cost trajectory) lands in a Postgres evidence store outside the
measured envelope, and every run is auto-scored against seeded targets with
known ground truth. The decision rule is evidence-gated: the LLM makes
falsifiable predictions, the pipeline verifies them (keep-rule v2 — RPS or
p95 classic keeps, verified-mechanism keeps for saddle traversal).

The source of truth is `docs/`: `v1.0-scope.md` (locked design + §10
resolutions), `v1.0-build-steps.md` (verify-gated build order), and
`open-questions.md` (what paper can't settle — check items off as runs
produce data). Modules: `evidence` (Postgres store) <- `target-runner`
(deterministic benchmark pipeline) <- `agent-core` (Spring AI loop);
`eval` (scoring harness) sits on top. The read-only reference repo with
ground-truth recordings and the docker/k6 envelope is
`C:/study/java-backend-quality-analyzer/`.

## Quick start

```bash
mvn -q package                                   # build all modules
docker compose -f docker/evidence/docker-compose.yml up -d   # evidence store (leave running)
git clone https://github.com/spring-projects/spring-petclinic targets/petclinic
```

Then follow `docs/v1.0-build-steps.md` from Step 0's verify gate onward.
