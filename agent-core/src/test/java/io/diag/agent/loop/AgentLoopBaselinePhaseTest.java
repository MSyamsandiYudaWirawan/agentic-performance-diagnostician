package io.diag.agent.loop;

import io.diag.agent.config.GenParams;
import io.diag.evidence.dto.BaselineReportsDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.dto.ThresholdsDto;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.FixTemplateRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class AgentLoopBaselinePhaseTest {

    @TempDir
    Path tempRepo;

    private FakeEvidenceService evidenceService;
    private FakeTargetPipeline targetPipeline;
    private FakeDecideTurn decideTurn;
    private LoopConfig loopConfig;
    private GenParams genParams;
    private ChangeApplier changeApplier;

    @BeforeEach
    void setUp() throws Exception {
        git(tempRepo, "init");
        git(tempRepo, "config", "user.email", "test@test.com");
        git(tempRepo, "config", "user.name", "Test");
        Files.writeString(tempRepo.resolve("README.md"), "test repo");
        git(tempRepo, "add", ".");
        git(tempRepo, "commit", "-m", "init");

        evidenceService = new FakeEvidenceService();
        targetPipeline = new FakeTargetPipeline();
        targetPipeline.withEvidenceService(evidenceService, () -> "test-run");
        decideTurn = new FakeDecideTurn();
        loopConfig = LoopConfig.defaults();
        genParams = new GenParams("test-prov", "test-mod", 0.0, 1000);
        changeApplier = new ChangeApplier(new FixTemplateRegistry());
    }

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

    private LoadReportDto createLoadDto(double rps, double p95) {
        return new LoadReportDto(
                "test-repo", "2026-10-08T00:00:00Z", rps, 1000L,
                new LatencyDto(p95 / 2, p95 / 2, p95, p95 * 1.2, p95 * 2),
                0.0, 1.0, new ThresholdsDto("PASS", List.of())
        );
    }

    private JfrReportDto createJfrDto() {
        return new JfrReportDto("run-id", "baseline-3",
                Map.of("JavaMonitorEnter", new SignalSummaryDto(100L, 1.0, 2.0, 3.0, 4.0, "WARN", List.of())),
                null);
    }

    @Test
    void findBaselineReportsGracefullyHandlesMultipleRunsAndMultipleLoadReports() {
        String sha = "abc123sha";

        // Older Run 1: has complete baseline + iteration load reports
        Run run1 = evidenceService.createRun("petclinic", "prov", "mod", "hash", "1.0", Map.of());
        run1.setOriginSha(sha);
        evidenceService.transitionRunStatus(run1.getId(), io.diag.evidence.RunStatus.RUNNING, io.diag.evidence.RunStatus.COMPLETED);
        evidenceService.recordBaseline(run1.getId(), 2500.0, 125.0, 190.0, 9.5, sha);

        evidenceService.createLoadReport(run1.getId(), "baseline-1", createLoadDto(185, 2550), null);
        evidenceService.createLoadReport(run1.getId(), "baseline-2", createLoadDto(190, 2500), null);
        evidenceService.createLoadReport(run1.getId(), "baseline-3", createLoadDto(195, 2480), null);
        evidenceService.createJfrReport(run1.getId(), "baseline-3", createJfrDto(), null);

        // Run 1 also has iteration reports (iter-1, iter-2)
        evidenceService.createLoadReport(run1.getId(), "iter-1", createLoadDto(210, 2300), null);
        evidenceService.createLoadReport(run1.getId(), "iter-2", createLoadDto(220, 2200), null);

        // Newer Run 2: died during baseline (only baseline-1, no baseline recorded on run row)
        Run run2 = evidenceService.createRun("petclinic", "prov", "mod", "hash", "1.0", Map.of());
        run2.setOriginSha(sha);
        evidenceService.createLoadReport(run2.getId(), "baseline-1", createLoadDto(180, 2600), null);
        evidenceService.transitionRunStatus(run2.getId(), io.diag.evidence.RunStatus.RUNNING, io.diag.evidence.RunStatus.ABORTED);

        // Query EvidenceService for sha
        Optional<BaselineReportsDto> result = evidenceService.findBaselineReportsByOriginSha(sha);

        assertThat(result).isPresent();
        BaselineReportsDto reports = result.get();
        assertThat(reports.runId()).isEqualTo(run1.getId());
        assertThat(reports.loadReports()).hasSize(3);
        assertThat(reports.loadReports().get(0).rps()).isEqualTo(185.0);
        assertThat(reports.loadReports().get(1).rps()).isEqualTo(190.0);
        assertThat(reports.loadReports().get(2).rps()).isEqualTo(195.0);
        assertThat(reports.jfrReport().signals()).containsKey("JavaMonitorEnter");
    }

    @Test
    void baselineDbHitPopulatesCacheAndSkips3CycleBenchmark() throws Exception {
        String sha = changeApplier.currentSha(tempRepo);

        // Previous run recorded baseline in DB
        Run priorRun = evidenceService.createRun("petclinic", "prov", "mod", "hash", "1.0", Map.of());
        priorRun.setOriginSha(sha);
        evidenceService.recordBaseline(priorRun.getId(), 2500.0, 125.0, 190.0, 9.5, sha);
        evidenceService.transitionRunStatus(priorRun.getId(), io.diag.evidence.RunStatus.RUNNING, io.diag.evidence.RunStatus.COMPLETED);

        evidenceService.createLoadReport(priorRun.getId(), "baseline-1", createLoadDto(185, 2550), null);
        evidenceService.createLoadReport(priorRun.getId(), "baseline-2", createLoadDto(190, 2500), null);
        evidenceService.createLoadReport(priorRun.getId(), "baseline-3", createLoadDto(195, 2480), null);
        evidenceService.createJfrReport(priorRun.getId(), "baseline-3", createJfrDto(), null);

        // Start new run with in-memory cache enabled but empty
        InMemoryBaselineCache cache = new InMemoryBaselineCache();
        assertThat(cache.lookup(sha)).isEmpty();

        // TargetPipeline has NO cycles queued — if it tries to benchmark, it will fail
        LoopConfig oneIterConfig = new LoopConfig(
                1, 3_600_000L, 500_000L, java.math.BigDecimal.valueOf(5), 10, 0.50,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO
        );

        // Configure decideTurn to return null to exit loop immediately after 1 iter
        decideTurn.enqueue(new DecideTurn.DecideResult(null, "stop", false, 0, 0, ""));

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                oneIterConfig, genParams, tempRepo, "petclinic", cache
        );

        String newRunId = loop.start();

        // 1. In-memory cache should now be populated!
        assertThat(cache.lookup(sha)).isPresent();
        assertThat(cache.lookup(sha).get().loadReports()).hasSize(3);

        // 2. New run row in DB should have baseline recorded
        Run newRun = evidenceService.findRun(newRunId).orElseThrow();
        assertThat(newRun.getBaselineP95Ms()).isEqualTo(2480.0); // min of 2550, 2500, 2480
        assertThat(newRun.getBaselineRps()).isEqualTo(195.0); // max of 185, 190, 195

        // 3. New run should have attached baseline load reports for resume support
        List<LoadReport> newRunReports = evidenceService.findLoadReports(newRunId);
        assertThat(newRunReports).hasSize(3);
    }

    @Test
    void finishPersistsTokenUsageAndCostOnRun() throws Exception {
        Run run = evidenceService.createRun("petclinic", "prov", "mod", "hash", "1.0", Map.of());
        String sha = changeApplier.currentSha(tempRepo);
        run.setOriginSha(sha);
        evidenceService.recordBaseline(run.getId(), 2500.0, 125.0, 190.0, 9.5, sha);

        evidenceService.createLoadReport(run.getId(), "baseline-1", createLoadDto(185, 2550), null);
        evidenceService.createLoadReport(run.getId(), "baseline-2", createLoadDto(190, 2500), null);
        evidenceService.createLoadReport(run.getId(), "baseline-3", createLoadDto(195, 2480), null);
        evidenceService.createJfrReport(run.getId(), "baseline-3", createJfrDto(), null);

        LoopConfig config = new LoopConfig(
                1, 3_600_000L, 500_000L, java.math.BigDecimal.valueOf(5), 10, 0.50,
                java.math.BigDecimal.valueOf(1.50), java.math.BigDecimal.valueOf(2.00)
        );

        // Decide turn returns 1000 tokens in, 500 tokens out, then invalid to end iter 1
        decideTurn.enqueue(new DecideTurn.DecideResult(null, "invalid", false, 1000L, 500L, ""));

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                config, genParams, tempRepo, "petclinic"
        );

        loop.start(run.getId());

        Run finishedRun = evidenceService.findRun(run.getId()).orElseThrow();
        assertThat(finishedRun.getTokensIn()).isEqualTo(1000L);
        assertThat(finishedRun.getTokensOut()).isEqualTo(500L);
        assertThat(finishedRun.getCostUsd()).isGreaterThan(java.math.BigDecimal.ZERO);
    }

    @Test
    void resumeAdvancesBaselineReferenceToLastKeptIteration() throws Exception {
        Run run = evidenceService.createRun("petclinic", "prov", "mod", "hash", "1.0", Map.of());
        String sha = changeApplier.currentSha(tempRepo);
        run.setOriginSha(sha);
        evidenceService.recordBaseline(run.getId(), 2500.0, 125.0, 190.0, 9.5, sha);

        evidenceService.createLoadReport(run.getId(), "baseline-1", createLoadDto(185, 2550), null);
        evidenceService.createLoadReport(run.getId(), "baseline-2", createLoadDto(190, 2500), null);
        evidenceService.createLoadReport(run.getId(), "baseline-3", createLoadDto(195, 2480), null);
        evidenceService.createJfrReport(run.getId(), "baseline-3", createJfrDto(), null);

        // Iteration 1 was KEPT with 230 RPS (higher than baseline 195 RPS)
        LoadReport iter1Report = evidenceService.createLoadReport(run.getId(), "iter-1", createLoadDto(230, 2200), null);
        evidenceService.createJfrReport(run.getId(), "iter-1", createJfrDto(), null);
        evidenceService.createIteration(run.getId(), 1,
                new io.diag.evidence.dto.HypothesisDto("H5", 0.9, "lock"),
                Map.of("H5", 0.2),
                new io.diag.evidence.dto.ChangeDto("template", null, "jar-unpack", Map.of()),
                "KEPT", sha, iter1Report.getId(), null, List.of(), "RPS", "kept iter 1");

        // Prepare loop for iteration 2
        LoopConfig config = new LoopConfig(
                2, 3_600_000L, 500_000L, java.math.BigDecimal.valueOf(5), 10, 0.50,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO
        );

        // Capture the DecideContext passed to decideTurn in iteration 2
        final java.util.concurrent.atomic.AtomicReference<DecideContext> capturedContext = new java.util.concurrent.atomic.AtomicReference<>();
        DecideTurn spyingDecideTurn = (n, ctx) -> {
            capturedContext.set(ctx);
            return new DecideTurn.DecideResult(null, "stop", false, 0, 0, "");
        };

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, spyingDecideTurn, targetPipeline,
                config, genParams, tempRepo, "petclinic"
        );

        loop.resume(run.getId());

        // Context received by LLM in iteration 2 must have the KEPT reference (230 RPS), NOT the original baseline (195 RPS)
        assertThat(capturedContext.get()).isNotNull();
        assertThat(capturedContext.get().reference().rps()).isEqualTo(230.0);
        assertThat(capturedContext.get().reference().latency().p95()).isEqualTo(2200.0);
    }
}
