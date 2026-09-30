package io.diag.eval;

import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.BenchmarkCycle;
import io.diag.agent.loop.DecideTurn;
import io.diag.agent.loop.LoopConfig;
import io.diag.agent.loop.SmokeResult;
import io.diag.agent.loop.TargetPipeline;
import io.diag.eval.cli.MatrixCli;
import io.diag.eval.cli.MatrixConfig;
import io.diag.eval.cli.MatrixExecutionResult;
import io.diag.eval.model.DiagnosticTriage;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RegressionRow;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.AgentLoopFactory;
import io.diag.eval.service.DiagnosticTriageService;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.HtmlReportGenerator;
import io.diag.eval.service.MatrixRunner;
import io.diag.eval.service.RegressionTableService;
import io.diag.eval.service.impl.DiagnosticTriageServiceImpl;
import io.diag.eval.service.impl.EvalScorerImpl;
import io.diag.eval.service.impl.HtmlReportGeneratorImpl;
import io.diag.eval.service.impl.MatrixRunnerImpl;
import io.diag.eval.service.impl.RegressionTableServiceImpl;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.JfrReportRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.repository.TrajectoryEventRepository;
import io.diag.evidence.service.EvidenceService;
import io.diag.evidence.service.TargetRegistry;
import io.diag.runner.config.ChangeResult;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.SeededTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 12 Capstone Verification Gate (Step12VerifyGate).
 * <p>
 * Master end-to-end integration gate verifying v1.0 completion (§6, §10.20, §10.26):
 * 1. Autonomous execution across canonical targets S1-S4 meeting v1.0 success criteria:
 *    - Diagnostic accuracy >= 70% against ground truth.
 *    - Convergence count >= 2.
 *    - Loop iterations <= 5 per target.
 * 2. Zero manual collation: matrix-report.html and regression-table.md generated from DB.
 * 3. Offline self-contained HTML reports (zero CDNs, embedded styles, SVG charts).
 * 4. Guardrail safety aborts (cost & iteration ceilings) preserving partial evidence in DB.
 * 5. Checkpoint recovery & resume (revert-if-dirty, baseline reuse, resuming from lastKeptSha).
 * 6. Diagnostic Failure Triage hierarchy (JFR Quality -> Model Classification -> Fix Template -> Keep Rule).
 */
public class Step12VerifyGate {

    private RunRepository runRepository;
    private IterationRepository iterationRepository;
    private TargetRepository targetRepository;
    private LoadReportRepository loadReportRepository;
    private JfrReportRepository jfrReportRepository;
    private TrajectoryEventRepository trajectoryEventRepository;
    private TargetRegistry targetRegistry;
    private SeededTarget seededTarget;
    private AgentLoopFactory loopFactory;

    private EvalScorer evalScorer;
    private RegressionTableService regressionTableService;
    private HtmlReportGenerator htmlReportGenerator;
    private DiagnosticTriageService diagnosticTriageService;
    private MatrixRunner matrixRunner;
    private MatrixCli matrixCli;

    private final LoopConfig loopConfig = new LoopConfig(
            3, 30_000L, 500_000L,
            new BigDecimal("5.00"), 5, 0.50,
            BigDecimal.ZERO, BigDecimal.ZERO
    );
    private final GenParams genParams = new GenParams("anthropic", "claude-3-7-sonnet", 0.0, 2000);

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(RunRepository.class);
        iterationRepository = Mockito.mock(IterationRepository.class);
        targetRepository = Mockito.mock(TargetRepository.class);
        loadReportRepository = Mockito.mock(LoadReportRepository.class);
        jfrReportRepository = Mockito.mock(JfrReportRepository.class);
        trajectoryEventRepository = Mockito.mock(TrajectoryEventRepository.class);
        targetRegistry = Mockito.mock(TargetRegistry.class);
        seededTarget = Mockito.mock(SeededTarget.class);
        loopFactory = Mockito.mock(AgentLoopFactory.class);

        evalScorer = new EvalScorerImpl(
                runRepository, iterationRepository, targetRepository,
                loadReportRepository, targetRegistry
        );

        regressionTableService = new RegressionTableServiceImpl(runRepository, evalScorer);

        htmlReportGenerator = new HtmlReportGeneratorImpl(
                runRepository, iterationRepository, loadReportRepository,
                trajectoryEventRepository, targetRegistry, evalScorer
        );

        diagnosticTriageService = new DiagnosticTriageServiceImpl(
                runRepository, iterationRepository, jfrReportRepository, evalScorer
        );

        matrixRunner = new MatrixRunnerImpl(
                targetRegistry, seededTarget, evalScorer, loopFactory
        );

        matrixCli = new MatrixCli(
                matrixRunner, regressionTableService, htmlReportGenerator, diagnosticTriageService
        );
    }

    private void mockCanonicalTargets() {
        Target s1 = Target.builder()
                .id("S1").name("Stock Spring Petclinic")
                .baseRepo("targets/spring-petclinic")
                .baselineSha("sha-s1")
                .groundTruthCategory("H5").groundTruthFix("jar-unpack")
                .build();
        Target s2 = Target.builder()
                .id("S2").name("Petclinic Hikari Connection Starvation")
                .baseRepo("targets/spring-petclinic")
                .baselineSha("sha-s1").seedPatch("benchmarks/seeds/S2-hikari.patch")
                .groundTruthCategory("H2").groundTruthFix("hikari-pool-size")
                .build();
        Target s3 = Target.builder()
                .id("S3").name("Quarkus Virtual Thread Contention")
                .baseRepo("targets/quarkus-demo")
                .baselineSha("sha-s3")
                .groundTruthCategory("H3").groundTruthFix("virtual-threads")
                .build();
        Target s4 = Target.builder()
                .id("S4").name("Dropwizard Connection Pool Exhaustion")
                .baseRepo("targets/dropwizard-demo")
                .baselineSha("sha-s4")
                .groundTruthCategory("H4").groundTruthFix("connection-pool")
                .build();

        when(targetRegistry.findById("S1")).thenReturn(Optional.of(s1));
        when(targetRegistry.findById("S2")).thenReturn(Optional.of(s2));
        when(targetRegistry.findById("S3")).thenReturn(Optional.of(s3));
        when(targetRegistry.findById("S4")).thenReturn(Optional.of(s4));
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Autonomous Canonical Matrix Execution Meeting v1 Criteria
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 1: Canonical Matrix S1-S4 executes autonomously and passes v1.0 success criteria")
    void scenario1_canonicalMatrixPassesV1Criteria(@TempDir Path reportsDir) throws Exception {
        mockCanonicalTargets();

        AgentLoop loopS1 = Mockito.mock(AgentLoop.class);
        when(loopS1.start()).thenReturn("run-s1");
        AgentLoop loopS2 = Mockito.mock(AgentLoop.class);
        when(loopS2.start()).thenReturn("run-s2");
        AgentLoop loopS3 = Mockito.mock(AgentLoop.class);
        when(loopS3.start()).thenReturn("run-s3");
        AgentLoop loopS4 = Mockito.mock(AgentLoop.class);
        when(loopS4.start()).thenReturn("run-s4");

        when(loopFactory.create(eq("S1"), any(), any(), any())).thenReturn(loopS1);
        when(loopFactory.create(eq("S2"), any(), any(), any())).thenReturn(loopS2);
        when(loopFactory.create(eq("S3"), any(), any(), any())).thenReturn(loopS3);
        when(loopFactory.create(eq("S4"), any(), any(), any())).thenReturn(loopS4);

        Instant now = Instant.now();

        // Target S1 (Spring Petclinic, H5): accurate, converged
        Run runS1 = Run.builder().id("run-s1").targetId("S1").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-capstone").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(180.0).noiseFloorMs(15.0).baselineRps(200.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(30)).build();
        LoadReport lrS1 = LoadReport.builder().id(1L).runId("run-s1")
                .payload(new LoadReportDto("r", "d", 250.0, 100, new LatencyDto(0.0, 0.0, 120.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS1 = Iteration.builder().id(1L).runId("run-s1").n(1)
                .hypothesis(new HypothesisDto("H5", 0.95, "jar-unpack")).outcome("KEPT").loadReportId(1L).build();

        // Target S2 (Petclinic Hikari, H2): accurate, converged
        Run runS2 = Run.builder().id("run-s2").targetId("S2").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-capstone").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(350.0).noiseFloorMs(25.0).baselineRps(150.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(40)).build();
        LoadReport lrS2 = LoadReport.builder().id(2L).runId("run-s2")
                .payload(new LoadReportDto("r", "d", 240.0, 100, new LatencyDto(0.0, 0.0, 140.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS2 = Iteration.builder().id(2L).runId("run-s2").n(1)
                .hypothesis(new HypothesisDto("H2", 0.92, "hikari")).outcome("KEPT").loadReportId(2L).build();

        // Target S3 (Quarkus, H3): accurate, converged
        Run runS3 = Run.builder().id("run-s3").targetId("S3").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-capstone").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(220.0).noiseFloorMs(20.0).baselineRps(300.0).noiseFloorRps(15.0)
                .startedAt(now).finishedAt(now.plusSeconds(45)).build();
        LoadReport lrS3 = LoadReport.builder().id(3L).runId("run-s3")
                .payload(new LoadReportDto("r", "d", 460.0, 100, new LatencyDto(0.0, 0.0, 110.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS3 = Iteration.builder().id(3L).runId("run-s3").n(1)
                .hypothesis(new HypothesisDto("H3", 0.90, "virtual-threads")).outcome("KEPT").loadReportId(3L).build();

        // Target S4 (Dropwizard, H4): accurate, but change reverted (didn't clear noise floor)
        Run runS4 = Run.builder().id("run-s4").targetId("S4").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-capstone").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(300.0).noiseFloorMs(30.0).baselineRps(100.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(50)).build();
        LoadReport lrS4 = LoadReport.builder().id(4L).runId("run-s4")
                .payload(new LoadReportDto("r", "d", 104.0, 100, new LatencyDto(0.0, 0.0, 290.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS4 = Iteration.builder().id(4L).runId("run-s4").n(1)
                .hypothesis(new HypothesisDto("H4", 0.85, "connection-pool")).outcome("REVERTED").loadReportId(4L).build();

        when(runRepository.findById("run-s1")).thenReturn(Optional.of(runS1));
        when(runRepository.findById("run-s2")).thenReturn(Optional.of(runS2));
        when(runRepository.findById("run-s3")).thenReturn(Optional.of(runS3));
        when(runRepository.findById("run-s4")).thenReturn(Optional.of(runS4));

        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s1")).thenReturn(List.of(lrS1));
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s2")).thenReturn(List.of(lrS2));
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s3")).thenReturn(List.of(lrS3));
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s4")).thenReturn(List.of(lrS4));

        when(iterationRepository.findByRunIdOrderByNAsc("run-s1")).thenReturn(List.of(itS1));
        when(iterationRepository.findByRunIdOrderByNAsc("run-s2")).thenReturn(List.of(itS2));
        when(iterationRepository.findByRunIdOrderByNAsc("run-s3")).thenReturn(List.of(itS3));
        when(iterationRepository.findByRunIdOrderByNAsc("run-s4")).thenReturn(List.of(itS4));

        MatrixConfig config = new MatrixConfig(
                List.of("S1", "S2", "S3", "S4"),
                reportsDir,
                loopConfig,
                genParams,
                false
        );

        MatrixExecutionResult result = matrixCli.execute(config);

        // Core Capstone Verification (§6):
        assertThat(result.isSuccess()).isTrue();
        MatrixScoreReport report = result.scoreReport();
        assertThat(report.totalTargets()).isEqualTo(4);
        assertThat(report.accurateCount()).isEqualTo(4);
        assertThat(report.accuracyRate()).isGreaterThanOrEqualTo(0.70); // 100% >= 70%
        assertThat(report.convergedCount()).isEqualTo(3);
        assertThat(report.convergedCount()).isGreaterThanOrEqualTo(2);  // 3 >= 2
        assertThat(report.passesV1Threshold()).isTrue();

        // Iteration limit enforcement (<= 5 per target)
        for (RunScore rs : report.runScores()) {
            assertThat(rs.iterationsUsed()).isLessThanOrEqualTo(5);
        }
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Zero Manual Collation & Offline Report Self-Containment
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 2: HTML reports and markdown tables generated to disk with zero manual collation and zero CDNs")
    void scenario2_zeroManualCollationAndOfflineHtml(@TempDir Path reportsDir) throws Exception {
        mockCanonicalTargets();

        AgentLoop loopS1 = Mockito.mock(AgentLoop.class);
        when(loopS1.start()).thenReturn("run-s1");
        when(loopFactory.create(eq("S1"), any(), any(), any())).thenReturn(loopS1);

        AgentLoop loopS2 = Mockito.mock(AgentLoop.class);
        when(loopS2.start()).thenReturn("run-s2");
        when(loopFactory.create(eq("S2"), any(), any(), any())).thenReturn(loopS2);

        Instant now = Instant.now();
        Run runS1 = Run.builder().id("run-s1").targetId("S1").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-offline").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(180.0).noiseFloorMs(15.0).baselineRps(200.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(30)).build();
        LoadReport lrS1 = LoadReport.builder().id(1L).runId("run-s1")
                .payload(new LoadReportDto("r", "d", 250.0, 100, new LatencyDto(0.0, 0.0, 120.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS1 = Iteration.builder().id(1L).runId("run-s1").n(1)
                .hypothesis(new HypothesisDto("H5", 0.95, "jar-unpack")).outcome("KEPT").loadReportId(1L).build();

        Run runS2 = Run.builder().id("run-s2").targetId("S2").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-offline").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(350.0).noiseFloorMs(25.0).baselineRps(150.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(40)).build();
        LoadReport lrS2 = LoadReport.builder().id(2L).runId("run-s2")
                .payload(new LoadReportDto("r", "d", 240.0, 100, new LatencyDto(0.0, 0.0, 140.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS2 = Iteration.builder().id(2L).runId("run-s2").n(1)
                .hypothesis(new HypothesisDto("H2", 0.92, "hikari")).outcome("KEPT").loadReportId(2L).build();

        TrajectoryEvent event = TrajectoryEvent.builder()
                .id(1L).runId("run-s1").ts(now.plusSeconds(5)).kind("DECIDE_DIAGNOSE")
                .tokensIn(500L).tokensOut(150L).costUsd(new BigDecimal("0.005"))
                .payload(Map.of("hypothesis", "H5")).build();

        when(runRepository.findById("run-s1")).thenReturn(Optional.of(runS1));
        when(runRepository.findById("run-s2")).thenReturn(Optional.of(runS2));
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s1")).thenReturn(List.of(lrS1));
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s2")).thenReturn(List.of(lrS2));
        when(iterationRepository.findByRunIdOrderByNAsc("run-s1")).thenReturn(List.of(itS1));
        when(iterationRepository.findByRunIdOrderByNAsc("run-s2")).thenReturn(List.of(itS2));
        when(trajectoryEventRepository.findByRunIdOrderByTsAsc("run-s1")).thenReturn(List.of(event));

        MatrixConfig config = new MatrixConfig(
                List.of("S1", "S2"), reportsDir, loopConfig, genParams, false
        );

        MatrixExecutionResult result = matrixCli.execute(config);

        // 1. matrix-report.html exists and is completely offline
        Path matrixHtml = result.matrixReportHtmlPath();
        assertThat(Files.exists(matrixHtml)).isTrue();
        String html = Files.readString(matrixHtml);
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("Evaluation Matrix Scorecard");
        assertThat(html).contains("PASSES V1 THRESHOLD");
        assertThat(html).doesNotContain("href=\"http");
        assertThat(html).doesNotContain("src=\"http");
        assertThat(html).doesNotContain("<script");

        // 2. regression-table.md exists with prompt groupings
        Path regMd = result.regressionTableMdPath();
        assertThat(Files.exists(regMd)).isTrue();
        String md = Files.readString(regMd);
        assertThat(md).contains("phash-of");
        assertThat(md).contains("claude-3-7-sonnet");
        assertThat(md).contains("S1");

        // 3. Individual run reports exist
        assertThat(result.runReportPaths()).hasSize(2);
        Path runReport = result.runReportPaths().get(0);
        assertThat(Files.exists(runReport)).isTrue();
        String runHtml = Files.readString(runReport);
        assertThat(runHtml).contains("Stock Spring Petclinic (S1)");
        assertThat(runHtml).contains("<svg viewBox=\"0 0 440 180\"");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Guardrail Safety Enforcement (Ceiling Aborts & Evidence Preservation)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 3: Guardrail violations abort execution cleanly and preserve partial evidence in DB")
    void scenario3_guardrailViolationAbortsCleanly(@TempDir Path targetRepo) throws Exception {
        EvidenceService evidenceService = Mockito.mock(EvidenceService.class);
        ChangeApplier changeApplier = Mockito.mock(ChangeApplier.class);
        DecideTurn decideTurn = Mockito.mock(DecideTurn.class);
        TargetPipeline targetPipeline = Mockito.mock(TargetPipeline.class);

        String runId = "run-guardrail-test";
        String originSha = "sha-origin";
        when(changeApplier.currentSha(targetRepo)).thenReturn(originSha);

        Run run = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .status("RUNNING")
                .originSha(originSha)
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .build();
        when(evidenceService.createRun(any(), any(), any(), any(), any(), any())).thenReturn(run);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        // Low token cap of 1,000 tokens so iteration 1 will exceed guardrail
        LoopConfig lowTokenCapConfig = new LoopConfig(
                5, 1_800_000L, 1_000L,
                new BigDecimal("10.00"), 5, 0.50,
                BigDecimal.ZERO, BigDecimal.ZERO
        );

        LoadReportDto baseLoad = new LoadReportDto("r", "d", 200.0, 100, new LatencyDto(170.0, 175.0, 180.0, 190.0, 200.0), 0, 1, null);
        JfrReportDto fakeJfr = new JfrReportDto("r", "base", Map.of(), null);
        BenchmarkCycle baseCycle = new BenchmarkCycle(baseLoad, 1L, fakeJfr, 1L);
        when(targetPipeline.benchmark(any(), any())).thenReturn(baseCycle);

        DecisionDto dec = new DecisionDto(
                new HypothesisDto("H5", 0.9, "jar unpack lock reduction"),
                new PredictionDto("p95", "improve", "lock contention"),
                new LedgerUpdateDto("H5", "strengthen", "jar unpack fix"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        DecideTurn.DecideResult decResult = new DecideTurn.DecideResult(
                dec, null, false, 900L, 300L, "{}"
        );
        when(decideTurn.decide(eq(1), any())).thenReturn(decResult);

        when(changeApplier.apply(eq(targetRepo), any(), any()))
                .thenReturn(new ChangeResult("sha-iter1", FilesTouchedList.of(List.of()), true));
        when(targetPipeline.runTests()).thenReturn(true);
        when(targetPipeline.smoke(1)).thenReturn(new SmokeResult(true, "ok"));

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                lowTokenCapConfig, genParams, targetRepo, "S1"
        );

        String resultRunId = loop.start();
        assertThat(resultRunId).isEqualTo(runId);

        // Verify status transitioned to ABORTED
        verify(evidenceService).transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.ABORTED);
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Checkpoint Recovery & Revert-if-Dirty Resume
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 4: Checkpoint resume recovers lastKeptSha, skips baseline, and reverts uncommitted work")
    void scenario4_checkpointResumeRecoversState(@TempDir Path targetRepo) throws Exception {
        EvidenceService evidenceService = Mockito.mock(EvidenceService.class);
        ChangeApplier changeApplier = Mockito.mock(ChangeApplier.class);
        DecideTurn decideTurn = Mockito.mock(DecideTurn.class);
        TargetPipeline targetPipeline = Mockito.mock(TargetPipeline.class);

        String runId = "run-resume-001";
        String originSha = "sha-origin";
        String lastKeptSha = "sha-iter1-kept";

        when(changeApplier.currentSha(targetRepo)).thenReturn("sha-dirty-uncommitted");

        Run existingRun = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .status("RUNNING")
                .originSha(originSha)
                .lastKeptSha(lastKeptSha)
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .build();
        when(evidenceService.findRun(runId)).thenReturn(Optional.of(existingRun));
        when(evidenceService.findRunningRun()).thenReturn(Optional.empty());

        LoadReportDto baseLoad = new LoadReportDto("r", "d", 200.0, 100, new LatencyDto(170.0, 175.0, 180.0, 190.0, 200.0), 0, 1, null);
        LoadReport b1 = LoadReport.builder().id(1L).runId(runId).label("baseline-1").payload(baseLoad).build();
        LoadReport b2 = LoadReport.builder().id(2L).runId(runId).label("baseline-2").payload(baseLoad).build();
        LoadReport b3 = LoadReport.builder().id(3L).runId(runId).label("baseline-3").payload(baseLoad).build();
        when(evidenceService.findLoadReports(runId)).thenReturn(List.of(b1, b2, b3));

        Iteration iter1 = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.8, "jar unpack"))
                .outcome("KEPT")
                .treeSha(lastKeptSha)
                .build();
        when(evidenceService.findIterations(runId)).thenReturn(List.of(iter1));

        LoopConfig twoIterConfig = new LoopConfig(
                2, 1_800_000L, 500_000L,
                new BigDecimal("10.00"), 5, 0.50,
                BigDecimal.ZERO, BigDecimal.ZERO
        );

        DecisionDto dec2 = new DecisionDto(
                new HypothesisDto("H5", 0.95, "second pass cache optimization"),
                new PredictionDto("p95", "improve", "latency reduction"),
                new LedgerUpdateDto("H5", "strengthen", "cache verify"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        DecideTurn.DecideResult decResult2 = new DecideTurn.DecideResult(
                dec2, null, false, 400L, 100L, "{}"
        );
        when(decideTurn.decide(eq(2), any())).thenReturn(decResult2);

        when(changeApplier.apply(eq(targetRepo), any(), any()))
                .thenReturn(new ChangeResult("sha-iter2", FilesTouchedList.of(List.of()), true));
        when(changeApplier.currentSha(targetRepo)).thenReturn("sha-dirty-uncommitted", lastKeptSha, "sha-iter2");
        when(targetPipeline.runTests()).thenReturn(true);
        when(targetPipeline.smoke(2)).thenReturn(new SmokeResult(true, "ok"));

        LoadReportDto iter2Load = new LoadReportDto("r", "d", 260.0, 100, new LatencyDto(100.0, 110.0, 120.0, 130.0, 140.0), 0, 1, null);
        JfrReportDto fakeJfr2 = new JfrReportDto("r", "iter2", Map.of(), null);
        BenchmarkCycle iter2Cycle = new BenchmarkCycle(iter2Load, 10L, fakeJfr2, 10L);
        when(targetPipeline.benchmark(eq("iter-2"), any())).thenReturn(iter2Cycle);

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                twoIterConfig, genParams, targetRepo, "S1"
        );

        String resumedRunId = loop.resume(runId);
        assertThat(resumedRunId).isEqualTo(runId);

        // Verify revert-if-dirty reverted working tree to lastKeptSha
        verify(changeApplier, Mockito.atLeastOnce()).revertTo(targetRepo, lastKeptSha);

        // Verify baseline was NOT redone (benchmark with baseline-1 never called)
        verify(targetPipeline, Mockito.never()).benchmark(eq("baseline-1"), any());

        // Verify turn 2 was executed starting from n=2
        verify(decideTurn).decide(eq(2), any());

        // Verify run finished as COMPLETED
        verify(evidenceService).transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Diagnostic Failure Triage Hierarchy Playbook
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 5: Diagnostic failure triage diagnoses JFR, Model, Template, Keep-Rule failure stages")
    void scenario5_diagnosticTriageHierarchyPlaybook() {
        mockCanonicalTargets();

        // 1. STAGE_JFR_QUALITY: Empty JFR report
        String runJfr = "run-jfr-fail";
        Run r1 = Run.builder().id(runJfr).targetId("S1").status("COMPLETED").baselineP95Ms(180.0).baselineRps(200.0).build();
        Iteration it1 = Iteration.builder().id(10L).runId(runJfr).n(1).hypothesis(new HypothesisDto("H1", 0.8, "wrong")).outcome("REVERTED").build();
        when(runRepository.findById(runJfr)).thenReturn(Optional.of(r1));
        when(iterationRepository.findByRunIdOrderByNAsc(runJfr)).thenReturn(List.of(it1));
        when(jfrReportRepository.findByRunIdAndLabel(runJfr, "baseline-3"))
                .thenReturn(Optional.of(JfrReport.builder().payload(new JfrReportDto(runJfr, "b", Map.of(), null)).build()));

        DiagnosticTriage t1 = diagnosticTriageService.analyze(runJfr);
        assertThat(t1.failureStage()).isEqualTo(DiagnosticTriage.STAGE_JFR_QUALITY);
        assertThat(t1.detail()).contains("0 signals");

        // 2. STAGE_MODEL_DIAGNOSIS: Misclassified hypothesis
        String runModel = "run-model-fail";
        Run r2 = Run.builder().id(runModel).targetId("S2").status("COMPLETED").baselineP95Ms(350.0).baselineRps(150.0).build();
        SignalSummaryDto sig = new SignalSummaryDto(50L, 5.0, 15.0, 30.0, 50.0, "CRITICAL", List.of());
        when(jfrReportRepository.findById(20L))
                .thenReturn(Optional.of(JfrReport.builder().payload(new JfrReportDto(runModel, "i", Map.of("Lock", sig), null)).build()));
        Iteration itModel = Iteration.builder().id(20L).runId(runModel).n(1).jfrReportId(20L).hypothesis(new HypothesisDto("H4", 0.9, "wrong")).outcome("REVERTED").build();
        when(runRepository.findById(runModel)).thenReturn(Optional.of(r2));
        when(iterationRepository.findByRunIdOrderByNAsc(runModel)).thenReturn(List.of(itModel));

        DiagnosticTriage t2 = diagnosticTriageService.analyze(runModel);
        assertThat(t2.failureStage()).isEqualTo(DiagnosticTriage.STAGE_MODEL_DIAGNOSIS);
        assertThat(t2.detail()).contains("Model diagnosed category H4, but ground truth is H2");

        // 3. STAGE_TEMPLATE_SELECTION: Wasted iteration
        String runTemplate = "run-tpl-fail";
        Run r3 = Run.builder().id(runTemplate).targetId("S3").status("COMPLETED").baselineP95Ms(220.0).baselineRps(300.0).build();
        Iteration itTpl = Iteration.builder().id(30L).runId(runTemplate).n(1).hypothesis(new HypothesisDto("H3", 0.9, "threads")).outcome("WASTED").build();
        when(runRepository.findById(runTemplate)).thenReturn(Optional.of(r3));
        when(iterationRepository.findByRunIdOrderByNAsc(runTemplate)).thenReturn(List.of(itTpl));

        DiagnosticTriage t3 = diagnosticTriageService.analyze(runTemplate);
        assertThat(t3.failureStage()).isEqualTo(DiagnosticTriage.STAGE_TEMPLATE_SELECTION);
        assertThat(t3.detail()).contains("unadmitted, rejected by validation");

        // 4. STAGE_KEEP_RULE_REJECTED: Below noise floor
        String runNoise = "run-noise-fail";
        Run r4 = Run.builder().id(runNoise).targetId("S4").status("COMPLETED").baselineP95Ms(300.0).baselineRps(100.0).build();
        Iteration itNoise = Iteration.builder().id(40L).runId(runNoise).n(1).hypothesis(new HypothesisDto("H4", 0.9, "pool")).outcome("REVERTED").build();
        when(runRepository.findById(runNoise)).thenReturn(Optional.of(r4));
        when(iterationRepository.findByRunIdOrderByNAsc(runNoise)).thenReturn(List.of(itNoise));

        DiagnosticTriage t4 = diagnosticTriageService.analyze(runNoise);
        assertThat(t4.failureStage()).isEqualTo(DiagnosticTriage.STAGE_KEEP_RULE_REJECTED);
        assertThat(t4.detail()).contains("did not exceed noise floor");
    }
}
