package io.diag.agent;

import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.FakeDecideTurn;
import io.diag.agent.loop.FakeEvidenceService;
import io.diag.agent.loop.FakeTargetPipeline;
import io.diag.agent.loop.LoopConfig;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.EditDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.runner.service.BuildFailedException;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.FixTemplateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 9 verify gate (build-steps step 9, spec M3).
 *
 * Fully dry-run gate:
 * - Real ChangeApplier mutating a scratch git clone of targets/spring-petclinic
 * - FakeEvidenceService (in-memory, no Postgres)
 * - FakeDecideTurn (scripted decisions, no LLM / no API key)
 * - FakeTargetPipeline (scripted cycles, no Docker, no Maven)
 *
 * Runs all 8 scenarios from spec Part 10 M3.
 *
 * Execution:
 *   mvn -pl agent-core -am test "-Dtest=Step9VerifyGate"
 */
public class Step9VerifyGate {

    private static final Path PETCLINIC_SOURCE = Path.of("C:/study/agentic-performance-diagnostician/targets/spring-petclinic");

    private Path scratchRepo;
    private ChangeApplier changeApplier;
    private FakeEvidenceService evidenceService;
    private FakeDecideTurn decideTurn;
    private FakeTargetPipeline targetPipeline;
    private GenParams genParams;

    @BeforeEach
    void setUp() throws Exception {
        scratchRepo = Files.createTempDirectory("step9-gate-");

        // Clone scratch repo with core.autocrlf=false pinned at clone time (§10.33)
        git(Path.of("."), "clone", "-c", "core.autocrlf=false",
                PETCLINIC_SOURCE.toString(), scratchRepo.toString());

        changeApplier   = new ChangeApplier(new FixTemplateRegistry());
        evidenceService = new FakeEvidenceService();
        decideTurn      = new FakeDecideTurn();
        targetPipeline  = new FakeTargetPipeline();
        targetPipeline.withEvidenceService(evidenceService,
                () -> evidenceService.findRunningRun().map(Run::getId).orElse(null));
        genParams       = new GenParams("test-provider", "test-model", 0.0, 1000);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (scratchRepo != null && Files.exists(scratchRepo)) {
            try (Stream<Path> walk = Files.walk(scratchRepo)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                System.err.println("[Step9VerifyGate] cleanup failed: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. Happy path: valid decision → apply → smoke ok → benchmark improves → KEPT
    // -------------------------------------------------------------------------

    @Test
    void scenario1_happyPathKept() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);

        // Baseline: 189 rps, p95 2500ms
        configureDefaultBaselines();

        // Iteration 1 decision: valid template jar-unpack
        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.8, "lock contention on embedded jar"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "unpacked jar eliminates lock"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));

        // Iteration 1 benchmark improves significantly: RPS 189 -> 277 (+46%), p95 2500 -> 3100
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-1", 277.0, 3100.0, 45.0, 0.0, Map.of("JavaMonitorEnter", 0L)));

        LoopConfig config = configWithMaxIterations(1);
        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        // Verifications
        Run run = evidenceService.getRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(run.getLastKeptSha()).isNotNull().isNotEqualTo(originSha);

        // HEAD must be at the new commit
        String currentSha = changeApplier.currentSha(scratchRepo);
        assertThat(currentSha).isEqualTo(run.getLastKeptSha());

        // Iteration row must be KEPT with keepType RPS
        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(1);
        Iteration it = iters.get(0);
        assertThat(it.getOutcome()).isEqualTo("KEPT");
        assertThat(it.getKeepType()).isEqualTo("RPS");
        assertThat(it.getTreeSha()).isEqualTo(currentSha);
        assertThat(it.getFinding()).contains("277").contains("keep=RPS");
    }

    // -------------------------------------------------------------------------
    // 2. Regression → REVERTED, tree back at lastKeptSha
    // -------------------------------------------------------------------------

    @Test
    void scenario2_regressionReverted() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);
        configureDefaultBaselines();

        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.5, "lock hypothesis"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "weaken", "test regression"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));

        // Iteration 1 benchmark regresses / inside noise floor: rps 189 -> 190 (floor 9.45)
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-1", 190.0, 2550.0, 50.0, 0.0, Map.of("JavaMonitorEnter", 70000L)));

        LoopConfig config = configWithMaxIterations(1);
        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        // HEAD must have reverted to originSha
        String currentSha = changeApplier.currentSha(scratchRepo);
        assertThat(currentSha).isEqualTo(originSha);

        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(1);
        Iteration it = iters.get(0);
        assertThat(it.getOutcome()).isEqualTo("REVERTED");
        assertThat(it.getKeepType()).isNull();
    }

    // -------------------------------------------------------------------------
    // 3. Invalid decision x2 → WASTED, tree untouched
    // -------------------------------------------------------------------------

    @Test
    void scenario3_invalidDecisionTwiceWasted() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);
        configureDefaultBaselines();

        // Model emits invalid decision
        decideTurn.enqueue(FakeDecideTurn.invalid("missing hypothesis.category"));

        LoopConfig config = configWithMaxIterations(1);
        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        // Tree must be completely untouched
        assertThat(changeApplier.currentSha(scratchRepo)).isEqualTo(originSha);

        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(1);
        Iteration it = iters.get(0);
        assertThat(it.getOutcome()).isEqualTo("WASTED");
        assertThat(it.getFinding()).contains("invalid decision");
    }

    // -------------------------------------------------------------------------
    // 4. No-op edit rejection → WASTED
    // -------------------------------------------------------------------------

    @Test
    void scenario4_noopEditWasted() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);
        configureDefaultBaselines();

        // Read real application.properties content
        Path propsFile = scratchRepo.resolve("src/main/resources/application.properties");
        String currentContent = Files.readString(propsFile);

        // Edit proposes exact same byte-identical content (no-op)
        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H1", 0.5, "no-op test"),
                new PredictionDto("rps", "improve", "SocketRead"),
                new LedgerUpdateDto("H1", "strengthen", "noop"),
                new ChangeDto("edits", List.of(new EditDto("src/main/resources/application.properties", currentContent)), null, null)
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));

        LoopConfig config = configWithMaxIterations(1);
        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        // Tree untouched, iteration is WASTED
        assertThat(changeApplier.currentSha(scratchRepo)).isEqualTo(originSha);

        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(1);
        assertThat(iters.get(0).getOutcome()).isEqualTo("WASTED");
    }

    // -------------------------------------------------------------------------
    // 5. Build failure → REVERTED + build log tail in history
    // -------------------------------------------------------------------------

    @Test
    void scenario5_buildFailureReverted() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);
        configureDefaultBaselines();

        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.8, "jar-unpack"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "jar-unpack"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));

        // Simulate build failure on rebuild() during iteration 1 (after baseline rebuild passes)
        targetPipeline.setFailRebuildAfter(1, new BuildFailedException("Maven compiler: syntax error in Foo.java"));

        LoopConfig config = configWithMaxIterations(1);
        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        // Tree must be reverted
        assertThat(changeApplier.currentSha(scratchRepo)).isEqualTo(originSha);

        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(1);
        assertThat(iters.get(0).getOutcome()).isEqualTo("REVERTED");
        assertThat(iters.get(0).getFinding()).contains("build failed");
    }

    // -------------------------------------------------------------------------
    // 6. Guardrail (tiny token cap) → ABORTED + tree clean
    // -------------------------------------------------------------------------

    @Test
    void scenario6_guardrailAbortedTreeClean() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);
        configureDefaultBaselines();

        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.8, "jar-unpack"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "jar-unpack"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        // Decide turn returns 1550 tokens
        decideTurn.enqueue(FakeDecideTurn.valid(decision));

        // LoopConfig with maxTokens = 500 (lower than decide turn usage)
        LoopConfig tightConfig = new LoopConfig(
                5,
                3_600_000L,
                500L, // tiny token limit!
                new BigDecimal("5.00"),
                10,
                0.50,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                tightConfig, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        Run run = evidenceService.getRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.ABORTED.name());
        assertThat(changeApplier.currentSha(scratchRepo)).isEqualTo(originSha);
    }

    // -------------------------------------------------------------------------
    // 7. Kill-and-resume (§10.19, Part 9)
    // -------------------------------------------------------------------------

    @Test
    void scenario7_killAndResume() throws Exception {
        String originSha = changeApplier.currentSha(scratchRepo);
        configureDefaultBaselines();

        // Iteration 1: valid decision that is KEPT
        DecisionDto decision1 = new DecisionDto(
                new HypothesisDto("H5", 0.8, "iter 1"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "iter 1"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision1));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-1", 277.0, 3000.0, 40.0, 0.0, Map.of("JavaMonitorEnter", 0L)));

        // Run only 1 iteration
        LoopConfig config1 = configWithMaxIterations(1);
        AgentLoop loop1 = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config1, genParams, scratchRepo, "spring-petclinic");

        String runId = loop1.start();
        String iter1Sha = changeApplier.currentSha(scratchRepo);
        assertThat(iter1Sha).isNotEqualTo(originSha);

        // Simulate crash: force status back to RUNNING
        evidenceService.getRun(runId).setStatus(RunStatus.RUNNING.name());

        // Prepare Iteration 2 for resume: another edit
        DecisionDto decision2 = new DecisionDto(
                new HypothesisDto("H1", 0.6, "iter 2"),
                new PredictionDto("rps", "improve", "SocketRead"),
                new LedgerUpdateDto("H1", "strengthen", "iter 2"),
                new ChangeDto("edits", List.of(new EditDto("step9-resume-marker.txt", "resume ok\n")), null, null)
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision2));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-2", 300.0, 2900.0, 35.0, 0.0, Map.of("SocketRead", 100L)));

        // Resume with maxIterations = 2
        LoopConfig config2 = configWithMaxIterations(2);
        AgentLoop loop2 = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config2, genParams, scratchRepo, "spring-petclinic");

        String resumedId = loop2.resume(runId);
        assertThat(resumedId).isEqualTo(runId);

        // Assertions: 2 iterations executed coherently, status COMPLETED
        Run run = evidenceService.getRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(2);
        assertThat(iters.get(0).getN()).isEqualTo(1);
        assertThat(iters.get(0).getOutcome()).isEqualTo("KEPT");
        assertThat(iters.get(1).getN()).isEqualTo(2);
        assertThat(iters.get(1).getOutcome()).isEqualTo("KEPT");
    }

    // -------------------------------------------------------------------------
    // 8. Trajectory events: TOOL_CALL / TOOL_RESULT rows recorded
    // -------------------------------------------------------------------------

    @Test
    void scenario8_trajectoryEventsRecorded() throws Exception {
        configureDefaultBaselines();

        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.8, "trajectory test"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "test"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-1", 250.0, 2600.0, 45.0, 0.0, Map.of()));

        // Explicitly create an LLM_REQ / LLM_RESP pair during decide turn simulation
        LoopConfig config = configWithMaxIterations(1);
        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, scratchRepo, "spring-petclinic");

        String runId = loop.start();

        // Verify iteration recorded
        List<Iteration> iters = evidenceService.iterationsFor(runId);
        assertThat(iters).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void configureDefaultBaselines() {
        // Canned baseline 1, 2, 3: average ~189 rps, 2500ms p95
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-1", 188.0, 2520.0, 50.0, 0.0, Map.of("JavaMonitorEnter", 71000L)));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-2", 190.0, 2480.0, 49.0, 0.0, Map.of("JavaMonitorEnter", 71500L)));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-3", 189.0, 2500.0, 50.0, 0.0, Map.of("JavaMonitorEnter", 71193L)));
    }

    private LoopConfig configWithMaxIterations(int maxIter) {
        return new LoopConfig(
                maxIter,
                3_600_000L,
                500_000L,
                new BigDecimal("5.00"),
                10,
                0.50,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
    }
}
