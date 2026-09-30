# Step 10 Specification — Target Registry + Seeds S2–S4

Companion to `v1.0-scope.md` (§3, §4, §10.23, §10.24, §10.29, §10.35) and `v1.0-build-steps.md` (Step 10).
Order and principles follow `TIGERSTYLE.md`: explicit failure modes, no silent swallows,
no big-bang integrations, deterministically testable milestones (M0–M5).

---

## Part 1 — Goal & Mental Model

### Goal
Transition the system from running against a single ad-hoc target (S1 stock petclinic) to managing a
**reproducible 4-target matrix (S1–S4)** with deterministic degradation seeds, ground-truth labels,
baseline caching, and admitted fix templates.

### The Mental Model
```
TargetRegistry (DB + configs)
      │
      ├── Target S1 (Stock PetClinic, pristine base, H5 lock)
      │      └── Fix: jar-unpack (admitted)
      │
      ├── Target S2 (PetClinic + Hikari pool=2 seed, H2 pool starvation)
      │      └── Fix: hikari-pool-size (admitted in M2)
      │
      ├── Target S3 (PetClinic + JVM -Xmx256m seed, H3 GC starvation)
      │      └── Fix: jvm-opts (admitted in M3)
      │
      └── Target S4 (Practice-MVC, app synchronized monitor hotspot)
             └── Fix: code-level lock narrowing / concurrent structure (M4)

SeededTarget Lifecycle:
Pristine Base ──[apply patch]──> Committed Seed (baselineSha) ──[run loop]──> Revert to baselineSha
```

---

## Part 2 — Key Architectural Decisions

### D1: Seed Patch Storage
- Seed patches for S2 and S3 are committed Git diffs / patch files stored under:
  `benchmarks/seeds/<targetId>.patch`
- S1 has no patch (`seed_patch = null`).
- S4 is ported from REF (`C:/study/java-backend-quality-analyzer/targets/practice-mvc`).

### D2: Baseline Caching Key Contract (§10.24, §10.30)
- The baseline cache key is a composite hash:
  `profile_hash = sha256(target_tree_sha + ":" + k6_script_sha + ":" + envelope_config_sha)`
- When a run initiates for target `T`:
  1. Check if a clean baseline exists in Evidence DB for `profile_hash`.
  2. If hit: load `(baseline_rps, baseline_p95, noise_floor_rps, noise_floor_ms, load_reports)` and skip the 3-cycle baseline execution (~6 min saved).
  3. If miss: run pristine 3-cycle baseline, record reports, and cache to DB.

### D3: Template Admission Rule (§10.35)
- No template enters `FixTemplateRegistry.ADMITTED` without a recorded **hand-validation run**.
- Unadmitted templates throw `TemplateNotAdmittedException`.
- S2 admits `hikari-pool-size`.
- S3 admits `jvm-opts`.

### D4: Git Working Tree Hygiene (§10.33)
- Windows CRLF causes phantom modifications.
- Pin `git -C <target> config core.autocrlf false`.
- Any seeding operation ends with a clean `git status --porcelain` check.

---

## Part 3 — Target Matrix Ground Truth (§3)

| ID | Target Base | Seed Patch / Degradation | Ground Truth Category | Dominant JFR Signal | Expected Fix | Level |
|:---|:---|:---|:---|:---|:---|:---|
| **S1** | Stock PetClinic | *(None / Pristine)* | **H5** (Lock contention) | `JavaMonitorEnter` (`UrlJarFiles$Cache`) | `jar-unpack` | Packaging |
| **S2** | PetClinic | `spring.datasource.hikari.maximumPoolSize=2` | **H2** (Pool starvation) | `ThreadPark` / Pool entry lock | `hikari-pool-size` | Config |
| **S3** | PetClinic | `-Xmx256m` in `compose-service.yml` | **H3** (GC exhaustion) | `GCPhasePause` CRITICAL | `jvm-opts` | Config |
| **S4** | Practice-MVC | Synchronized hotspot on app method | **H5** (App monitor lock) | `JavaMonitorEnter` on app class | Lock narrowing | Code |

---

## Part 4 — Database Contracts & Entities

### 1. `target` Table (Flyway V1 schema.md)
```sql
CREATE TABLE target (
    id                    VARCHAR(40) PRIMARY KEY,
    name                  VARCHAR(256) NOT NULL,
    base_repo             VARCHAR(500) NOT NULL,
    seed_patch            TEXT,
    baseline_sha          VARCHAR(40) NOT NULL,
    ground_truth_category VARCHAR(10) NOT NULL,
    ground_truth_fix      TEXT NOT NULL,
    profile_hash          VARCHAR(64) NOT NULL
);
```

### 2. Java Entity & Repository
- `io.diag.evidence.entity.Target` (Spring Data JDBC, `Persistable<String>`).
- `io.diag.evidence.repository.TargetRepository`.

---

## Part 5 — Milestones & Verification Gates

### Milestone 0: Target Registry & DB Persistence (M0)
- Goal: `TargetRegistry` service in `evidence` / `agent-core` that populates and reads target metadata from Postgres.
- Test: Unit/DB tests populating S1–S4 definitions, asserting round-trip equality and `profile_hash` computation.
- Verify Gate: `TargetRegistryTest` green (no Docker, no LLM).

### Milestone 1: SeededTarget Lifecycle & Git Revert Isolation (M1)
- Goal: Apply patch to pristine base clone, commit with message `[seed] <targetId>: <desc>`, verify clean working tree, and verify `revertTo(baselineSha)` resets cleanly.
- Test: `SeededTargetTest` against scratch git clone.
- Verify Gate: Scratch repo clean after apply and revert (`git status --porcelain` empty).

### Milestone 2: Seed S2 + `hikari-pool-size` Admission (M2)
- Goal:
  1. Commit `benchmarks/seeds/S2-hikari.patch` (`spring.datasource.hikari.maximumPoolSize=2`).
  2. Run JFR sanity check on S2: confirm dominant signal is `ThreadPark` / connection pool wait.
  3. Hand-validate `hikari-pool-size` template: set `poolSize=10` or `20`, run benchmark once, verify connection wait eliminates, record as `fix-validation` run.
  4. Move `"hikari-pool-size"` into `FixTemplateRegistry.ADMITTED`.
- Verify Gate: JFR confirms H2 dominant signal; fix-validation proves template potency.

### Milestone 3: Seed S3 + `jvm-opts` Admission (M3)
- Goal:
  1. Commit `benchmarks/seeds/S3-jvm.patch` (`JAVA_OPTS=-Xms256m -Xmx256m`).
  2. Run JFR sanity check on S3: confirm dominant signal is `GCPhasePause` CRITICAL.
  3. Implement `JvmOptsTemplate` (modifies `JAVA_OPTS` in `compose-service.yml`).
  4. Hand-validate `jvm-opts` template: restore memory flags, run benchmark, verify GC pause collapses, record as `fix-validation` run.
  5. Move `"jvm-opts"` into `FixTemplateRegistry.ADMITTED`.
- Verify Gate: JFR confirms H3 dominant signal; fix-validation proves template potency.

### Milestone 4: Seed S4 (Practice-MVC) (M4)
- Goal:
  1. Port `practice-mvc` from REF into `targets/practice-mvc`.
  2. Sanity-check baseline JFR: confirm dominant signal is `JavaMonitorEnter` on application controller/service class.
  3. Hand-validate lock narrowing fix (replace synchronized block with `ConcurrentHashMap` or fine-grained lock), benchmark once, record `fix-validation`.
- Verify Gate: JFR confirms H5 application monitor contention; hand fix eliminates it.

### Milestone 5: Baseline Caching & Full Step 10 Verify Gate (M5)
- Goal:
  1. Implement `BaselineCache` service integrated with `TargetPipeline`.
  2. Cache hit returns persisted baseline, skipping 3-cycle k6 execution.
  3. `Step10VerifyGate`:
     - Lists all 4 targets in `TargetRegistry`.
     - Seeding + reset round-trips cleanly across all targets.
     - Baseline cache hit/miss behavior proven.
     - All 4 target baselines persisted in Evidence DB.
- Verify Gate: `mvn test -pl agent-core -Dtest=Step10VerifyGate` green.

---

## Part 6 — TigerStyle Enforcement Checklist for Step 10
- [ ] No stream pipelines longer than 3 operations without intermediate variables.
- [ ] Every `catch` block handles, compensates (with comment), or rethrows.
- [ ] Public methods validate non-null inputs (`Objects.requireNonNull`).
- [ ] All git interactions enforce `core.autocrlf=false` and check `--porcelain`.
- [ ] No unadmitted templates allowed into `FixTemplateRegistry` without hand validation (§10.35).
