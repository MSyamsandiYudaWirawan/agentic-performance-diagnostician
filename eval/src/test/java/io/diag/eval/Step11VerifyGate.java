package io.diag.eval;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.LoopConfig;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RegressionRow;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.AgentLoopFactory;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.HtmlReportGenerator;
import io.diag.eval.service.MatrixRunner;
import io.diag.eval.service.RegressionTableService;
import io.diag.eval.service.impl.EvalScorerImpl;
import io.diag.eval.service.impl.HtmlReportGeneratorImpl;
import io.diag.eval.service.impl.MatrixRunnerImpl;
import io.diag.eval.service.impl.RegressionTableServiceImpl;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.repository.IterationRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 11 Full Verification Gate (§6, §10.20, §10.26, Step 11 Spec M4).
 * <p>
 * Verifies:
 * 1. Scoring logic against ground truth (accuracy, convergence, efficiency).
 * 2. Regression table prompt-tweak grouping and Markdown rendering.
 * 3. Self-contained offline HTML reports (zero CDNs, inline SVGs, timelines).
 * 4. Matrix runner execution and threshold verification (passesV1Threshold).
 * 5. Edge cases: inaccurate / non-converged runs and fallback hypotheses.
 */
public class Step11VerifyGate {

    private RunRepository runRepository;
    private IterationRepository iterationRepository;
    private TargetRepository targetRepository;
    private LoadReportRepository loadReportRepository;
    private TrajectoryEventRepository trajectoryEventRepository;
    private TargetRegistry targetRegistry;
    private SeededTarget seededTarget;
    private AgentLoopFactory loopFactory;

    private EvalScorer evalScorer;
    private RegressionTableService regressionTableService;
    private HtmlReportGenerator htmlReportGenerator;
    private MatrixRunner matrixRunner;

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

        matrixRunner = new MatrixRunnerImpl(
                targetRegistry, seededTarget, evalScorer, loopFactory
        );
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Scoring against Ground Truth (Accurate + Converged)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 1: S1 run scored correctly against ground truth (H5 jar-unpack)")
    void scenario1_scoredRunAgainstGroundTruth() {
        String runId = "run-s1-test";
        Instant start = Instant.parse("2026-09-30T10:00:00Z");
        Instant finish = Instant.parse("2026-09-30T10:02:30Z");

        Run run = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .promptHash("phash-baseline")
                .aggregatorVersion("1.0")
                .status("COMPLETED")
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .startedAt(start)
                .finishedAt(finish)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target targetS1 = Target.builder()
                .id("S1")
                .name("Stock Spring Petclinic")
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .build();
        when(targetRegistry.findById("S1")).thenReturn(Optional.of(targetS1));

        LoadReportDto lrDto = new LoadReportDto(
                "repo", "2026-09-30", 250.0, 5000,
                new LatencyDto(100.0, 110.0, 120.0, 140.0, 150.0),
                0.0, 1.0, null
        );
        LoadReport lr = LoadReport.builder()
                .id(1L)
                .runId(runId)
                .label("iter-1")
                .payload(lrDto)
                .build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId)).thenReturn(List.of(lr));

        Iteration iter = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.95, "jar unpack lock elimination"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("KEPT")
                .loadReportId(1L)
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        RunScore score = evalScorer.scoreRun(runId);

        assertThat(score).isNotNull();
        assertThat(score.runId()).isEqualTo(runId);
        assertThat(score.targetId()).isEqualTo("S1");
        assertThat(score.diagnosedCategory()).isEqualTo("H5");
        assertThat(score.groundTruthCategory()).isEqualTo("H5");
        assertThat(score.accurate()).isTrue();
        assertThat(score.baselineP95Ms()).isEqualTo(180.0);
        assertThat(score.finalP95Ms()).isEqualTo(120.0);
        assertThat(score.p95DeltaMs()).isEqualTo(60.0);
        assertThat(score.converged()).isTrue();
        assertThat(score.baselineRps()).isEqualTo(200.0);
        assertThat(score.finalRps()).isEqualTo(250.0);
        assertThat(score.rpsDelta()).isEqualTo(50.0);
        assertThat(score.rpsImproved()).isTrue();
        assertThat(score.iterationsUsed()).isEqualTo(1);
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Prompt Tweak Produces Distinct Regression Table Groups
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 2: Prompt tweak produces second regression-table group with comparison metrics")
    void scenario2_regressionTablePromptTweakGrouping() {
        String runId1 = "run-prompt-a";
        String runId2 = "run-prompt-b";
        Instant now = Instant.now();

        Run run1 = Run.builder()
                .id(runId1)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .promptHash("phash-original")
                .aggregatorVersion("1.0")
                .status("COMPLETED")
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .startedAt(now)
                .finishedAt(now.plusSeconds(60))
                .build();

        Run run2 = Run.builder()
                .id(runId2)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .promptHash("phash-tweaked")
                .aggregatorVersion("1.0")
                .status("COMPLETED")
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .startedAt(now.plusSeconds(70))
                .finishedAt(now.plusSeconds(130))
                .build();

        when(runRepository.findById(runId1)).thenReturn(Optional.of(run1));
        when(runRepository.findById(runId2)).thenReturn(Optional.of(run2));

        Target targetS1 = Target.builder()
                .id("S1")
                .name("Stock Spring Petclinic")
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .build();
        when(targetRegistry.findById("S1")).thenReturn(Optional.of(targetS1));

        LoadReport lr1 = LoadReport.builder().id(1L).runId(runId1)
                .payload(new LoadReportDto("r", "d", 240.0, 100, new LatencyDto(0.0, 0.0, 130.0, 0.0, 0.0), 0, 1, null))
                .build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId1)).thenReturn(List.of(lr1));
        when(iterationRepository.findByRunIdOrderByNAsc(runId1)).thenReturn(List.of(
                Iteration.builder().id(1L).runId(runId1).n(1).hypothesis(new HypothesisDto("H5", 0.9, "jar")).outcome("KEPT").loadReportId(1L).build()
        ));

        LoadReport lr2 = LoadReport.builder().id(2L).runId(runId2)
                .payload(new LoadReportDto("r", "d", 270.0, 100, new LatencyDto(0.0, 0.0, 100.0, 0.0, 0.0), 0, 1, null))
                .build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId2)).thenReturn(List.of(lr2));
        when(iterationRepository.findByRunIdOrderByNAsc(runId2)).thenReturn(List.of(
                Iteration.builder().id(2L).runId(runId2).n(1).hypothesis(new HypothesisDto("H5", 0.99, "jar")).outcome("KEPT").loadReportId(2L).build()
        ));

        List<RegressionRow> table = regressionTableService.computeTable(List.of(runId1, runId2));

        // Two distinct prompt hashes must produce two distinct rows
        assertThat(table).hasSize(2);
        assertThat(table.get(0).key().promptHash()).isEqualTo("phash-original");
        assertThat(table.get(1).key().promptHash()).isEqualTo("phash-tweaked");

        assertThat(table.get(0).meanP95DeltaMs()).isEqualTo(50.0);
        assertThat(table.get(1).meanP95DeltaMs()).isEqualTo(80.0);

        String markdown = regressionTableService.renderMarkdown(table);
        assertThat(markdown).contains("phash-or");
        assertThat(markdown).contains("phash-tw");
        assertThat(markdown).contains("+50.0 ms");
        assertThat(markdown).contains("+80.0 ms");
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Self-Contained Offline HTML Report Generated to Disk
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 3: Self-contained offline HTML report opens standalone with zero external dependencies")
    void scenario3_htmlReportOfflineStandalone(@TempDir Path tempDir) throws IOException {
        String runId = "run-report-s1";
        Instant now = Instant.now();

        Run run = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .promptHash("phash-prod")
                .aggregatorVersion("1.0")
                .status("COMPLETED")
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .startedAt(now)
                .finishedAt(now.plusSeconds(45))
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target target = Target.builder()
                .id("S1")
                .name("Stock Spring Petclinic")
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .build();
        when(targetRegistry.findById("S1")).thenReturn(Optional.of(target));

        LoadReport lr = LoadReport.builder()
                .id(1L)
                .runId(runId)
                .payload(new LoadReportDto("repo", "2026-09-30", 250.0, 5000, new LatencyDto(0.0, 0.0, 120.0, 0.0, 0.0), 0, 1, null))
                .k6SummaryPath("runs/run-report-s1/k6-summary.json")
                .build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId)).thenReturn(List.of(lr));

        Iteration iter = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.9, "jar-unpack"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("KEPT")
                .loadReportId(1L)
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        TrajectoryEvent event = TrajectoryEvent.builder()
                .id(1L)
                .runId(runId)
                .ts(now.plusSeconds(2))
                .kind("LLM_RESP")
                .tokensIn(800L)
                .tokensOut(200L)
                .costUsd(new BigDecimal("0.01"))
                .payload(Map.of("action", "diagnose"))
                .build();
        when(trajectoryEventRepository.findByRunIdOrderByTsAsc(runId)).thenReturn(List.of(event));

        Path reportFile = tempDir.resolve("reports/run-report-s1.html");
        Path exported = htmlReportGenerator.exportRunReport(runId, reportFile);

        assertThat(Files.exists(exported)).isTrue();
        String html = Files.readString(exported);

        // Verification of offline self-containment (§10.26)
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<style>");
        assertThat(html).doesNotContain("href=\"http");
        assertThat(html).doesNotContain("src=\"http");
        assertThat(html).doesNotContain("@import");
        assertThat(html).doesNotContain("<script");

        // Verification of scorecard badges
        assertThat(html).contains("ACCURATE");
        assertThat(html).contains("CONVERGED");
        assertThat(html).contains("Stock Spring Petclinic (S1)");

        // Verification of inline SVGs
        assertThat(html).contains("<svg viewBox=\"0 0 440 180\"");
        assertThat(html).contains("Floor");

        // Verification of timeline and trajectory
        assertThat(html).contains("jar-unpack");
        assertThat(html).contains("k6-summary.json");
        assertThat(html).contains("LLM_RESP");
        assertThat(html).contains("800 / 200");
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Matrix Execution and Threshold Passing (§6, passesV1Threshold)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 4: Matrix runner executes S1-S2, aggregates scores, and passes V1 threshold")
    void scenario4_matrixRunnerThresholdPass() throws Exception {
        Target s1 = Target.builder()
                .id("S1")
                .name("Stock Petclinic")
                .baseRepo("targets/spring-petclinic")
                .baselineSha("sha-s1")
                .seedPatch(null)
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .build();

        Target s2 = Target.builder()
                .id("S2")
                .name("Petclinic Hikari")
                .baseRepo("targets/spring-petclinic")
                .baselineSha("sha-s1")
                .seedPatch("benchmarks/seeds/S2-hikari.patch")
                .groundTruthCategory("H2")
                .groundTruthFix("hikari-pool-size")
                .build();

        when(targetRegistry.findById("S1")).thenReturn(Optional.of(s1));
        when(targetRegistry.findById("S2")).thenReturn(Optional.of(s2));

        AgentLoop loopS1 = Mockito.mock(AgentLoop.class);
        when(loopS1.start()).thenReturn("run-s1");

        AgentLoop loopS2 = Mockito.mock(AgentLoop.class);
        when(loopS2.start()).thenReturn("run-s2");

        when(loopFactory.create(eq("S1"), any(), eq(loopConfig), eq(genParams))).thenReturn(loopS1);
        when(loopFactory.create(eq("S2"), any(), eq(loopConfig), eq(genParams))).thenReturn(loopS2);

        Instant now = Instant.now();
        Run runS1 = Run.builder().id("run-s1").targetId("S1").status("COMPLETED")
                .baselineP95Ms(180.0).noiseFloorMs(15.0).baselineRps(200.0).noiseFloorRps(10.0).build();
        Run runS2 = Run.builder().id("run-s2").targetId("S2").status("COMPLETED")
                .baselineP95Ms(300.0).noiseFloorMs(25.0).baselineRps(100.0).noiseFloorRps(10.0).build();
        when(runRepository.findById("run-s1")).thenReturn(Optional.of(runS1));
        when(runRepository.findById("run-s2")).thenReturn(Optional.of(runS2));

        LoadReport lrS1 = LoadReport.builder().id(1L).runId("run-s1")
                .payload(new LoadReportDto("r", "d", 250.0, 100, new LatencyDto(0.0, 0.0, 120.0, 0.0, 0.0), 0, 1, null)).build();
        LoadReport lrS2 = LoadReport.builder().id(2L).runId("run-s2")
                .payload(new LoadReportDto("r", "d", 220.0, 100, new LatencyDto(0.0, 0.0, 150.0, 0.0, 0.0), 0, 1, null)).build();
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s1")).thenReturn(List.of(lrS1));
        when(loadReportRepository.findByRunIdOrderByIdAsc("run-s2")).thenReturn(List.of(lrS2));

        when(iterationRepository.findByRunIdOrderByNAsc("run-s1")).thenReturn(List.of(
                Iteration.builder().id(1L).runId("run-s1").n(1).hypothesis(new HypothesisDto("H5", 0.9, "jar")).outcome("KEPT").loadReportId(1L).build()
        ));
        when(iterationRepository.findByRunIdOrderByNAsc("run-s2")).thenReturn(List.of(
                Iteration.builder().id(2L).runId("run-s2").n(1).hypothesis(new HypothesisDto("H2", 0.9, "hikari")).outcome("KEPT").loadReportId(2L).build()
        ));

        MatrixScoreReport matrixReport = matrixRunner.runMatrix(List.of("S1", "S2"), loopConfig, genParams);

        assertThat(matrixReport.totalTargets()).isEqualTo(2);
        assertThat(matrixReport.accurateCount()).isEqualTo(2);
        assertThat(matrixReport.accuracyRate()).isEqualTo(1.0);
        assertThat(matrixReport.convergedCount()).isEqualTo(2);
        assertThat(matrixReport.convergenceRate()).isEqualTo(1.0);
        assertThat(matrixReport.passesV1Threshold()).isTrue();

        // Also test matrix HTML generation
        String matrixHtml = htmlReportGenerator.generateMatrixReport(List.of("run-s1", "run-s2"));
        assertThat(matrixHtml).contains("PASSES V1 THRESHOLD");
        assertThat(matrixHtml).contains("100.0%");
        assertThat(matrixHtml).contains("run-s1");
        assertThat(matrixHtml).contains("run-s2");
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Edge Case — Inaccurate and Non-converged Run
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Scenario 5: Inaccurate diagnosis and non-converged run flagged properly in scoring")
    void scenario5_inaccurateAndNonConverged() {
        String runId = "run-fail-001";
        Run run = Run.builder()
                .id(runId)
                .targetId("S3")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .status("COMPLETED")
                .baselineP95Ms(200.0)
                .noiseFloorMs(20.0)
                .baselineRps(150.0)
                .noiseFloorRps(10.0)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target targetS3 = Target.builder()
                .id("S3")
                .name("Petclinic JVM Memory")
                .groundTruthCategory("H3")
                .groundTruthFix("jvm-opts")
                .build();
        when(targetRegistry.findById("S3")).thenReturn(Optional.of(targetS3));

        // Agent made wrong hypothesis (H1) and iteration was reverted
        LoadReport lr = LoadReport.builder()
                .id(1L)
                .runId(runId)
                .payload(new LoadReportDto("repo", "2026-09-30", 145.0, 1000, new LatencyDto(0.0, 0.0, 205.0, 0.0, 0.0), 0, 1, null))
                .build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId)).thenReturn(List.of(lr));

        Iteration iter = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H1", 0.7, "wrong hypothesis CPU bottleneck"))
                .outcome("REVERTED")
                .loadReportId(1L)
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        RunScore score = evalScorer.scoreRun(runId);

        assertThat(score.accurate()).isFalse();
        assertThat(score.converged()).isFalse();
        assertThat(score.diagnosedCategory()).isEqualTo("H1");
        assertThat(score.groundTruthCategory()).isEqualTo("H3");
        assertThat(score.p95DeltaMs()).isEqualTo(0.0); // No kept iteration, final latency remains baseline
    }
}
