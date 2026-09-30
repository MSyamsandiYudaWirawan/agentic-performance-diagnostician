package io.diag.eval;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.LoopConfig;
import io.diag.eval.cli.MatrixCli;
import io.diag.eval.cli.MatrixConfig;
import io.diag.eval.cli.MatrixExecutionResult;
import io.diag.eval.model.DiagnosticTriage;
import io.diag.eval.model.MatrixScoreReport;
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
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
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
import io.diag.evidence.service.TargetRegistry;
import io.diag.runner.service.SeededTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
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
import static org.mockito.Mockito.when;

/**
 * Step 12 Milestone 3 Verify Gate (MatrixEvaluationTest).
 * Evaluates the full end-to-end matrix across canonical targets S1-S4 (§6, §10.20):
 * 1. Autonomous multi-target execution with baseline caching.
 * 2. Automated scoring against ground truth meeting v1 criteria: accuracy >= 70%, converged >= 2.
 * 3. Zero manual collation: matrix-report.html, regression-table.md, and run HTML reports written to disk.
 * 4. Sub-threshold evaluation handling and triage diagnostics.
 */
public class MatrixEvaluationTest {

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
                .id("S3").name("Quarkus Virtual Thread Pinning")
                .baseRepo("targets/quarkus-demo")
                .baselineSha("sha-s3")
                .groundTruthCategory("H3").groundTruthFix("virtual-threads")
                .build();
        Target s4 = Target.builder()
                .id("S4").name("Dropwizard DB Thread Exhaustion")
                .baseRepo("targets/dropwizard-demo")
                .baselineSha("sha-s4")
                .groundTruthCategory("H4").groundTruthFix("connection-pool")
                .build();

        when(targetRegistry.findById("S1")).thenReturn(Optional.of(s1));
        when(targetRegistry.findById("S2")).thenReturn(Optional.of(s2));
        when(targetRegistry.findById("S3")).thenReturn(Optional.of(s3));
        when(targetRegistry.findById("S4")).thenReturn(Optional.of(s4));
    }

    @Test
    @DisplayName("Matrix execution across S1-S4 satisfies v1.0 success criteria (accuracy >= 70%, converged >= 2)")
    void matrixExecution_acrossS1toS4_passesV1Criteria(@TempDir Path reportsDir) throws Exception {
        mockCanonicalTargets();

        // Configure mock loops for S1-S4
        AgentLoop loopS1 = Mockito.mock(AgentLoop.class);
        when(loopS1.start()).thenReturn("run-s1");
        AgentLoop loopS2 = Mockito.mock(AgentLoop.class);
        when(loopS2.start()).thenReturn("run-s2");
        AgentLoop loopS3 = Mockito.mock(AgentLoop.class);
        when(loopS3.start()).thenReturn("run-s3");
        AgentLoop loopS4 = Mockito.mock(AgentLoop.class);
        when(loopS4.start()).thenReturn("run-s4");

        when(loopFactory.create(eq("S1"), any(), eq(loopConfig), eq(genParams))).thenReturn(loopS1);
        when(loopFactory.create(eq("S2"), any(), eq(loopConfig), eq(genParams))).thenReturn(loopS2);
        when(loopFactory.create(eq("S3"), any(), eq(loopConfig), eq(genParams))).thenReturn(loopS3);
        when(loopFactory.create(eq("S4"), any(), eq(loopConfig), eq(genParams))).thenReturn(loopS4);

        Instant now = Instant.now();

        // S1: H5, accurate, converged (p95: 180 -> 120ms, RPS: 200 -> 250)
        Run runS1 = Run.builder().id("run-s1").targetId("S1").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-v1").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(180.0).noiseFloorMs(15.0).baselineRps(200.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(30)).build();
        LoadReport lrS1 = LoadReport.builder().id(1L).runId("run-s1")
                .payload(new LoadReportDto("r", "d", 250.0, 100, new LatencyDto(0.0, 0.0, 120.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS1 = Iteration.builder().id(1L).runId("run-s1").n(1)
                .hypothesis(new HypothesisDto("H5", 0.95, "jar-unpack")).outcome("KEPT").loadReportId(1L).build();

        // S2: H2, accurate, converged (p95: 350 -> 140ms, RPS: 150 -> 230)
        Run runS2 = Run.builder().id("run-s2").targetId("S2").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-v1").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(350.0).noiseFloorMs(25.0).baselineRps(150.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(40)).build();
        LoadReport lrS2 = LoadReport.builder().id(2L).runId("run-s2")
                .payload(new LoadReportDto("r", "d", 230.0, 100, new LatencyDto(0.0, 0.0, 140.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS2 = Iteration.builder().id(2L).runId("run-s2").n(1)
                .hypothesis(new HypothesisDto("H2", 0.90, "hikari")).outcome("KEPT").loadReportId(2L).build();

        // S3: H3, accurate, converged (p95: 220 -> 110ms, RPS: 300 -> 450)
        Run runS3 = Run.builder().id("run-s3").targetId("S3").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-v1").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(220.0).noiseFloorMs(20.0).baselineRps(300.0).noiseFloorRps(15.0)
                .startedAt(now).finishedAt(now.plusSeconds(45)).build();
        LoadReport lrS3 = LoadReport.builder().id(3L).runId("run-s3")
                .payload(new LoadReportDto("r", "d", 450.0, 100, new LatencyDto(0.0, 0.0, 110.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS3 = Iteration.builder().id(3L).runId("run-s3").n(1)
                .hypothesis(new HypothesisDto("H3", 0.92, "virtual-threads")).outcome("KEPT").loadReportId(3L).build();

        // S4: H4, accurate diagnosis, but reverted change (did not meet noise floor)
        Run runS4 = Run.builder().id("run-s4").targetId("S4").provider("anthropic").model("claude-3-7-sonnet")
                .promptHash("phash-v1").aggregatorVersion("1.0").status("COMPLETED")
                .baselineP95Ms(300.0).noiseFloorMs(30.0).baselineRps(100.0).noiseFloorRps(10.0)
                .startedAt(now).finishedAt(now.plusSeconds(50)).build();
        LoadReport lrS4 = LoadReport.builder().id(4L).runId("run-s4")
                .payload(new LoadReportDto("r", "d", 102.0, 100, new LatencyDto(0.0, 0.0, 290.0, 0.0, 0.0), 0, 1, null)).build();
        Iteration itS4 = Iteration.builder().id(4L).runId("run-s4").n(1)
                .hypothesis(new HypothesisDto("H4", 0.88, "pool-size")).outcome("REVERTED").loadReportId(4L).build();

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

        // Execute Matrix CLI
        MatrixExecutionResult result = matrixCli.execute(config);

        // 1. Success criteria assertions (§6)
        assertThat(result.isSuccess()).isTrue();
        MatrixScoreReport report = result.scoreReport();
        assertThat(report.totalTargets()).isEqualTo(4);
        assertThat(report.accurateCount()).isEqualTo(4);
        assertThat(report.accuracyRate()).isEqualTo(1.0); // 100% >= 70% threshold
        assertThat(report.convergedCount()).isEqualTo(3); // 3 >= 2 threshold
        assertThat(report.convergenceRate()).isEqualTo(0.75);
        assertThat(report.passesV1Threshold()).isTrue();

        // 2. Zero manual collation: verify generated files on disk
        Path matrixHtmlPath = result.matrixReportHtmlPath();
        assertThat(Files.exists(matrixHtmlPath)).isTrue();
        String htmlContent = Files.readString(matrixHtmlPath);
        assertThat(htmlContent).contains("PASSES V1 THRESHOLD");
        assertThat(htmlContent).contains("S1").contains("S2").contains("S3").contains("S4");
        // Offline validation
        assertThat(htmlContent).doesNotContain("href=\"http");
        assertThat(htmlContent).doesNotContain("src=\"http");

        Path regressionMdPath = result.regressionTableMdPath();
        assertThat(Files.exists(regressionMdPath)).isTrue();
        String mdContent = Files.readString(regressionMdPath);
        assertThat(mdContent).contains("phash-v1");
        assertThat(mdContent).contains("Diagnostic Failure Triage");
        assertThat(mdContent).contains("KEEP_RULE_REJECTED"); // S4 failed keep rule

        // 3. Individual run reports exported under runs/
        assertThat(result.runReportPaths()).hasSize(4);
        for (Path runReport : result.runReportPaths()) {
            assertThat(Files.exists(runReport)).isTrue();
            String runHtml = Files.readString(runReport);
            assertThat(runHtml).startsWith("<!DOCTYPE html>");
        }

        // 4. Verify triages
        assertThat(result.triages()).hasSize(4);
        DiagnosticTriage s4Triage = result.triages().stream()
                .filter(t -> "S4".equals(t.targetId()))
                .findFirst()
                .orElseThrow();
        assertThat(s4Triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_KEEP_RULE_REJECTED);
    }

    @Test
    @DisplayName("Matrix execution when below threshold returns exit code 1 and triages root cause")
    void matrixExecution_belowThreshold_returnsFailedExitCode(@TempDir Path reportsDir) throws Exception {
        mockCanonicalTargets();

        AgentLoop loopS1 = Mockito.mock(AgentLoop.class);
        when(loopS1.start()).thenReturn("run-fail-s1");
        AgentLoop loopS2 = Mockito.mock(AgentLoop.class);
        when(loopS2.start()).thenReturn("run-fail-s2");

        when(loopFactory.create(eq("S1"), any(), any(), any())).thenReturn(loopS1);
        when(loopFactory.create(eq("S2"), any(), any(), any())).thenReturn(loopS2);

        // S1: Inaccurate diagnosis (picked H1 instead of H5)
        Run runS1 = Run.builder().id("run-fail-s1").targetId("S1").status("COMPLETED")
                .baselineP95Ms(180.0).noiseFloorMs(15.0).baselineRps(200.0).noiseFloorRps(10.0).build();
        Iteration itS1 = Iteration.builder().id(1L).runId("run-fail-s1").n(1)
                .hypothesis(new HypothesisDto("H1", 0.90, "wrong")).outcome("REVERTED").build();

        // S2: Inaccurate diagnosis (picked H4 instead of H2)
        Run runS2 = Run.builder().id("run-fail-s2").targetId("S2").status("COMPLETED")
                .baselineP95Ms(350.0).noiseFloorMs(25.0).baselineRps(150.0).noiseFloorRps(10.0).build();
        Iteration itS2 = Iteration.builder().id(2L).runId("run-fail-s2").n(1)
                .hypothesis(new HypothesisDto("H4", 0.90, "wrong")).outcome("REVERTED").build();

        when(runRepository.findById("run-fail-s1")).thenReturn(Optional.of(runS1));
        when(runRepository.findById("run-fail-s2")).thenReturn(Optional.of(runS2));
        when(iterationRepository.findByRunIdOrderByNAsc("run-fail-s1")).thenReturn(List.of(itS1));
        when(iterationRepository.findByRunIdOrderByNAsc("run-fail-s2")).thenReturn(List.of(itS2));

        String[] args = new String[]{
                "--targets=S1,S2",
                "--reports-dir=" + reportsDir.toString()
        };

        int exitCode = matrixCli.run(args, Map.of());
        assertThat(exitCode).isEqualTo(MatrixCli.EXIT_THRESHOLD_FAILED);
    }
}
