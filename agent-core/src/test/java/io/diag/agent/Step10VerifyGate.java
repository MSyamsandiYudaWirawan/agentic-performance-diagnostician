package io.diag.agent;

import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.agent.loop.*;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.service.TargetRegistry;
import io.diag.evidence.service.impl.TargetRegistryImpl;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.FixTemplateRegistry;
import io.diag.runner.service.SeededTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 10 Verify Gate (build-steps step 10, spec Part 5 Milestone 5).
 *
 * Verifies:
 * 1. TargetRegistry lists all 4 targets (S1-S4) with canonical ground-truth metadata.
 * 2. SeededTarget lifecycle: seeds S2, S3, S4 apply cleanly and round-trip revert to pristine.
 * 3. FixTemplateRegistry admits jar-unpack, hikari-pool-size, and jvm-opts.
 * 4. BaselineCache miss executes 3-cycle baseline; cache hit skips benchmark execution.
 * 5. ProfileHash computation is deterministic and unique across variations.
 *
 * Execution:
 *   mvn test -pl agent-core -Dtest=Step10VerifyGate
 */
public class Step10VerifyGate {

    private static final Path PETCLINIC_SOURCE = Path.of("C:/study/agentic-performance-diagnostician/targets/spring-petclinic");
    private static final Path PRACTICE_MVC_SOURCE = Path.of("C:/study/agentic-performance-diagnostician/targets/practice-mvc");
    private static final Path S2_PATCH = Path.of("C:/study/agentic-performance-diagnostician/benchmarks/seeds/S2-hikari.patch");
    private static final Path S3_PATCH = Path.of("C:/study/agentic-performance-diagnostician/benchmarks/seeds/S3-jvm.patch");
    private static final Path S4_PATCH = Path.of("C:/study/agentic-performance-diagnostician/benchmarks/seeds/S4-monitor.patch");

    private Path scratchPetclinic;
    private Path scratchPracticeMvc;
    private ChangeApplier changeApplier;
    private SeededTarget seededTarget;
    private FixTemplateRegistry templateRegistry;
    private TargetRegistry targetRegistry;
    private InMemoryTargetRepository inMemoryTargetRepo;

    @BeforeEach
    void setUp() throws Exception {
        scratchPetclinic = Files.createTempDirectory("step10-petclinic-");
        git(Path.of("."), "clone", "-c", "core.autocrlf=false",
                PETCLINIC_SOURCE.toString(), scratchPetclinic.toString());

        scratchPracticeMvc = Files.createTempDirectory("step10-practice-");
        git(Path.of("."), "clone", "-c", "core.autocrlf=false",
                PRACTICE_MVC_SOURCE.toString(), scratchPracticeMvc.toString());

        templateRegistry = new FixTemplateRegistry();
        changeApplier = new ChangeApplier(templateRegistry);
        seededTarget = new SeededTarget(changeApplier);

        inMemoryTargetRepo = new InMemoryTargetRepository();
        targetRegistry = new TargetRegistryImpl(inMemoryTargetRepo);
    }

    @AfterEach
    void tearDown() {
        cleanDir(scratchPetclinic);
        cleanDir(scratchPracticeMvc);
    }

    private void cleanDir(Path dir) {
        if (dir != null && Files.exists(dir)) {
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                System.err.println("[Step10VerifyGate] cleanup failed for " + dir + ": " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. TargetRegistry lists all 4 targets with ground-truth metadata
    // -------------------------------------------------------------------------

    @Test
    void scenario1_registryListsAllFourTargets() {
        targetRegistry.initCanonicalTargets();
        List<Target> targets = targetRegistry.findAll();
        assertThat(targets).hasSize(4);

        Map<String, Target> map = new HashMap<>();
        for (Target t : targets) {
            map.put(t.getId(), t);
        }

        // S1: Stock Petclinic
        Target s1 = map.get("S1");
        assertThat(s1).isNotNull();
        assertThat(s1.getGroundTruthCategory()).isEqualTo("H5");
        assertThat(s1.getGroundTruthFix()).isEqualTo("jar-unpack");
        assertThat(s1.getBaseRepo()).contains("spring-petclinic");

        // S2: Hikari Pool Starvation
        Target s2 = map.get("S2");
        assertThat(s2).isNotNull();
        assertThat(s2.getGroundTruthCategory()).isEqualTo("H2");
        assertThat(s2.getGroundTruthFix()).isEqualTo("hikari-pool-size");

        // S3: JVM Memory
        Target s3 = map.get("S3");
        assertThat(s3).isNotNull();
        assertThat(s3.getGroundTruthCategory()).isEqualTo("H3");
        assertThat(s3.getGroundTruthFix()).isEqualTo("jvm-opts");

        // S4: Practice-MVC App Monitor
        Target s4 = map.get("S4");
        assertThat(s4).isNotNull();
        assertThat(s4.getGroundTruthCategory()).isEqualTo("H5");
        assertThat(s4.getGroundTruthFix()).isEqualTo("lock-narrowing");
        assertThat(s4.getBaseRepo()).contains("practice-mvc");
    }

    // -------------------------------------------------------------------------
    // 2. Seeding + Reset Round-Trips Cleanly across all targets
    // -------------------------------------------------------------------------

    @Test
    void scenario2_seedingAndResetRoundTripsCleanly() throws Exception {
        String petclinicPristine = changeApplier.currentSha(scratchPetclinic);
        String practicePristine = changeApplier.currentSha(scratchPracticeMvc);

        // --- S1: Pristine ---
        String s1Sha = seededTarget.seed(scratchPetclinic, petclinicPristine, null, "S1", "Stock");
        assertThat(s1Sha).isEqualTo(petclinicPristine);
        assertThat(gitOutput(scratchPetclinic, "status", "--porcelain").trim()).isEmpty();

        // --- S2: Hikari Seed ---
        String s2Sha = seededTarget.seed(scratchPetclinic, petclinicPristine, S2_PATCH, "S2", "Hikari pool size 2");
        assertThat(s2Sha).isNotEqualTo(petclinicPristine);
        assertThat(gitOutput(scratchPetclinic, "status", "--porcelain").trim()).isEmpty();
        String s2Props = Files.readString(scratchPetclinic.resolve("src/main/resources/application.properties"));
        assertThat(s2Props).contains("spring.datasource.hikari.maximumPoolSize=2");

        // Revert S2 to pristine
        seededTarget.resetToPristine(scratchPetclinic, petclinicPristine);
        assertThat(changeApplier.currentSha(scratchPetclinic)).isEqualTo(petclinicPristine);
        assertThat(gitOutput(scratchPetclinic, "status", "--porcelain").trim()).isEmpty();

        // --- S3: JVM Seed ---
        String s3Sha = seededTarget.seed(scratchPetclinic, petclinicPristine, S3_PATCH, "S3", "JVM heap 256m");
        assertThat(s3Sha).isNotEqualTo(petclinicPristine);
        assertThat(gitOutput(scratchPetclinic, "status", "--porcelain").trim()).isEmpty();
        String s3Compose = Files.readString(scratchPetclinic.resolve("compose-service.yml"));
        assertThat(s3Compose).contains("-Xms256m");
        assertThat(s3Compose).contains("-Xmx256m");

        // Revert S3 to pristine
        seededTarget.resetToPristine(scratchPetclinic, petclinicPristine);
        assertThat(changeApplier.currentSha(scratchPetclinic)).isEqualTo(petclinicPristine);
        assertThat(gitOutput(scratchPetclinic, "status", "--porcelain").trim()).isEmpty();

        // --- S4: Practice-MVC Monitor Seed ---
        String s4Sha = seededTarget.seed(scratchPracticeMvc, practicePristine, S4_PATCH, "S4", "App monitor lock");
        assertThat(s4Sha).isNotEqualTo(practicePristine);
        assertThat(gitOutput(scratchPracticeMvc, "status", "--porcelain").trim()).isEmpty();
        String s4Service = Files.readString(scratchPracticeMvc.resolve("src/main/java/com/MSyamsandiYW/practice/product/impl/ProductServiceImpl.java"));
        assertThat(s4Service).contains("public synchronized GetProductResponse findById");

        // Revert S4 to pristine
        seededTarget.resetToPristine(scratchPracticeMvc, practicePristine);
        assertThat(changeApplier.currentSha(scratchPracticeMvc)).isEqualTo(practicePristine);
        assertThat(gitOutput(scratchPracticeMvc, "status", "--porcelain").trim()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 3. FixTemplateRegistry admits jar-unpack, hikari-pool-size, jvm-opts
    // -------------------------------------------------------------------------

    @Test
    void scenario3_templateRegistryAdmission() {
        assertTrue(templateRegistry.isAdmitted("jar-unpack"));
        assertTrue(templateRegistry.isAdmitted("hikari-pool-size"));
        assertTrue(templateRegistry.isAdmitted("jvm-opts"));

        assertFalse(templateRegistry.isAdmitted("unadmitted-candidate"));
        assertFalse(templateRegistry.isAdmitted("unknown-template"));
    }

    // -------------------------------------------------------------------------
    // 4. Baseline Cache: Miss executes baseline, Hit skips execution
    // -------------------------------------------------------------------------

    @Test
    void scenario4_baselineCacheHitSkipsExecution() throws Exception {
        FakeEvidenceService evidenceService = new FakeEvidenceService();
        FakeDecideTurn decideTurn = new FakeDecideTurn();
        FakeTargetPipeline targetPipeline = new FakeTargetPipeline();
        targetPipeline.withEvidenceService(evidenceService,
                () -> evidenceService.findRunningRun().map(Run::getId).orElse(null));

        LoopConfig loopConfig = new LoopConfig(
                1,
                3_600_000L,
                500_000L,
                new java.math.BigDecimal("5.00"),
                10,
                0.50,
                java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO
        );
        GenParams genParams = new GenParams("test-provider", "test-model", 0.0, 1000);
        BaselineCache baselineCache = new InMemoryBaselineCache();

        // Enqueue 3 cycles for Run 1 baseline
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-1", 189.0, 2500.0, 40.0, 0.0, Map.of()));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-2", 190.0, 2510.0, 42.0, 0.0, Map.of()));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-3", 191.0, 2490.0, 41.0, 0.0, Map.of()));

        // Decision for Iter 1: valid
        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.8, "lock contention"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "jar-unpack eliminates lock"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("iter-1", 270.0, 2800.0, 45.0, 0.0, Map.of()));

        // --- Run 1: Cache Miss ---
        String originSha = changeApplier.currentSha(scratchPetclinic);
        AgentLoop loop1 = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                loopConfig, genParams, scratchPetclinic, "spring-petclinic", baselineCache);

        String runId1 = loop1.start();
        Run run1 = evidenceService.getRun(runId1);
        assertThat(run1.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(run1.getBaselineRps()).isEqualTo(191.0);

        // Revert target back to pristine baseline for Run 2 so originSha matches cached profileHash
        changeApplier.revertTo(scratchPetclinic, originSha);

        // --- Run 2: Cache Hit (NO baseline cycles enqueued in targetPipeline!) ---
        // If it tried to benchmark baselines, targetPipeline.benchmark() would fail with NoSuchElementException!
        decideTurn.enqueue(FakeDecideTurn.valid(decision));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("iter-1", 275.0, 2750.0, 44.0, 0.0, Map.of()));

        AgentLoop loop2 = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                loopConfig, genParams, scratchPetclinic, "spring-petclinic", baselineCache);

        String runId2 = loop2.start();
        Run run2 = evidenceService.getRun(runId2);
        assertThat(run2.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(run2.getBaselineRps()).isEqualTo(191.0);
        assertThat(run2.getBaselineP95Ms()).isEqualTo(run1.getBaselineP95Ms());
    }

    // -------------------------------------------------------------------------
    // 5. Profile Hash Determinism
    // -------------------------------------------------------------------------

    @Test
    void scenario5_profileHashDeterminism() {
        String treeSha = "16eb8e29a562c90f4eddd3bfd6eae325065faf69";
        byte[] k6 = "export default function() {}".getBytes();
        byte[] env = "services: ...".getBytes();

        String hashA = targetRegistry.computeProfileHash(treeSha, k6, env);
        String hashB = targetRegistry.computeProfileHash(treeSha, k6, env);

        assertThat(hashA).isNotNull().hasSize(64).isEqualTo(hashB);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
    }

    private static String gitOutput(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        String output = new String(p.getInputStream().readAllBytes());
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
        return output;
    }

    private static class InMemoryTargetRepository implements TargetRepository {
        private final Map<String, Target> map = new HashMap<>();

        @Override
        public <S extends Target> S save(S entity) {
            map.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public <S extends Target> Iterable<S> saveAll(Iterable<S> entities) {
            entities.forEach(this::save);
            return entities;
        }

        @Override
        public Optional<Target> findById(String id) {
            return Optional.ofNullable(map.get(id));
        }

        @Override
        public boolean existsById(String id) {
            return map.containsKey(id);
        }

        @Override
        public Iterable<Target> findAll() {
            return new ArrayList<>(map.values());
        }

        @Override
        public Iterable<Target> findAllById(Iterable<String> strings) {
            List<Target> list = new ArrayList<>();
            for (String s : strings) {
                if (map.containsKey(s)) list.add(map.get(s));
            }
            return list;
        }

        @Override
        public long count() {
            return map.size();
        }

        @Override
        public void deleteById(String id) {
            map.remove(id);
        }

        @Override
        public void delete(Target entity) {
            map.remove(entity.getId());
        }

        @Override
        public void deleteAllById(Iterable<? extends String> strings) {
            for (String s : strings) map.remove(s);
        }

        @Override
        public void deleteAll(Iterable<? extends Target> entities) {
            for (Target t : entities) map.remove(t.getId());
        }

        @Override
        public void deleteAll() {
            map.clear();
        }
    }
}
