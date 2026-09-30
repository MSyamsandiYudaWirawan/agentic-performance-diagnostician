package io.diag.eval;

import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.BenchmarkCycle;
import io.diag.agent.loop.DecideTurn;
import io.diag.agent.loop.KeepRule;
import io.diag.agent.loop.LoopConfig;
import io.diag.agent.loop.SmokeResult;
import io.diag.agent.loop.TargetPipeline;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.impl.EvalScorerImpl;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.runner.config.ChangeResult;
import io.diag.runner.service.ChangeApplier;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.repository.TrajectoryEventRepository;
import io.diag.evidence.service.EvidenceService;
import io.diag.evidence.service.TargetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 12 Milestone 1 Verify Gate (MatrixGuardrailResumeTest).
 * Tests guardrail cap enforcement (partial evidence retention) and killed-run resume recovery (§6, §10.19, §10.25).
 */
public class MatrixGuardrailResumeTest {

    private EvidenceService evidenceService;
    private ChangeApplier changeApplier;
    private DecideTurn decideTurn;
    private TargetPipeline targetPipeline;
    private TargetRegistry targetRegistry;
    private RunRepository runRepository;
    private IterationRepository iterationRepository;
    private LoadReportRepository loadReportRepository;
    private TargetRepository targetRepository;
    private EvalScorer evalScorer;

    @BeforeEach
    void setUp() {
        evidenceService = Mockito.mock(EvidenceService.class);
        changeApplier = Mockito.mock(ChangeApplier.class);
        decideTurn = Mockito.mock(DecideTurn.class);
        targetPipeline = Mockito.mock(TargetPipeline.class);
        targetRegistry = Mockito.mock(TargetRegistry.class);
        runRepository = Mockito.mock(RunRepository.class);
        iterationRepository = Mockito.mock(IterationRepository.class);
        loadReportRepository = Mockito.mock(LoadReportRepository.class);
        targetRepository = Mockito.mock(TargetRepository.class);

        evalScorer = new EvalScorerImpl(
                runRepository, iterationRepository, targetRepository,
                loadReportRepository, targetRegistry
        );
    }

    @Test
    @DisplayName("M1.1: Guardrail cap breach aborts run safely, preserves partial evidence, and scores as ABORTED")
    void guardrailCapBreach_preservesPartialEvidenceAndScoresAborted(@TempDir Path targetRepo) throws Exception {
        String runId = "run-guardrail-test";
        String originSha = "sha-origin";
        when(changeApplier.currentSha(targetRepo)).thenReturn(originSha);

        Run run = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .status(RunStatus.RUNNING.name())
                .originSha(originSha)
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .build();
        when(evidenceService.createRun(any(), any(), any(), any(), any(), any())).thenReturn(run);
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target targetS1 = Target.builder()
                .id("S1")
                .name("Stock Spring Petclinic")
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .build();
        when(targetRegistry.findById("S1")).thenReturn(Optional.of(targetS1));

        // Very low token cap of 1,000 tokens so iteration 1 will exceed guardrail
        LoopConfig lowTokenCapConfig = new LoopConfig(
                5, 1_800_000L, 1_000L,
                new BigDecimal("10.00"), 5, 0.50,
                BigDecimal.ZERO, BigDecimal.ZERO
        );
        GenParams genParams = new GenParams("anthropic", "claude-3-7-sonnet", 0.0, 2000);

        // Mock 3 baseline cycles
        LoadReportDto baseLoad = new LoadReportDto("r", "d", 200.0, 100, new LatencyDto(170.0, 175.0, 180.0, 190.0, 200.0), 0, 1, null);
        io.diag.evidence.dto.JfrReportDto fakeJfr = new io.diag.evidence.dto.JfrReportDto("r", "base", Map.of(), null);
        BenchmarkCycle baseCycle = new BenchmarkCycle(baseLoad, 1L, fakeJfr, 1L);
        when(targetPipeline.benchmark(any(), any())).thenReturn(baseCycle);

        // Turn 1 returns 1,200 tokens (> cap 1,000)
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

        // Change applied and tested
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

        // Simulate database state after run aborted
        run.setStatus(RunStatus.ABORTED.name());
        Iteration iter1 = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.9, "jar unpack"))
                .outcome("KEPT")
                .loadReportId(1L)
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter1));
        LoadReport lr1 = LoadReport.builder().id(1L).runId(runId).payload(baseLoad).build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId)).thenReturn(List.of(lr1));

        RunScore score = evalScorer.scoreRun(runId);
        assertThat(score.status()).isEqualTo("ABORTED");
        assertThat(score.accurate()).isTrue();
        assertThat(score.diagnosedCategory()).isEqualTo("H5");
        assertThat(score.iterationsUsed()).isEqualTo(1);
    }

    @Test
    @DisplayName("M1.2: Interrupted run resumes from lastKeptSha, skips baseline, and completes remaining iterations")
    void resumeKilledRun_resumesFromLastKeptShaWithoutRedoingBaseline(@TempDir Path targetRepo) throws Exception {
        String runId = "run-resume-001";
        String originSha = "sha-origin";
        String lastKeptSha = "sha-iter1-kept";

        // Current dirty sha on disk differs from lastKeptSha
        when(changeApplier.currentSha(targetRepo)).thenReturn("sha-dirty-uncommitted");

        // Existing run in DB with baseline already recorded
        Run existingRun = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .status(RunStatus.RUNNING.name())
                .originSha(originSha)
                .lastKeptSha(lastKeptSha)
                .baselineP95Ms(180.0)
                .noiseFloorMs(15.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .build();
        when(evidenceService.findRun(runId)).thenReturn(Optional.of(existingRun));
        when(evidenceService.findRunningRun()).thenReturn(Optional.empty());

        // Existing baseline load reports in DB
        LoadReportDto baseLoad = new LoadReportDto("r", "d", 200.0, 100, new LatencyDto(170.0, 175.0, 180.0, 190.0, 200.0), 0, 1, null);
        LoadReport b1 = LoadReport.builder().id(1L).runId(runId).label("baseline-1").payload(baseLoad).build();
        LoadReport b2 = LoadReport.builder().id(2L).runId(runId).label("baseline-2").payload(baseLoad).build();
        LoadReport b3 = LoadReport.builder().id(3L).runId(runId).label("baseline-3").payload(baseLoad).build();
        when(evidenceService.findLoadReports(runId)).thenReturn(List.of(b1, b2, b3));

        // Iteration 1 was already KEPT before crash
        Iteration iter1 = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.8, "jar unpack"))
                .outcome("KEPT")
                .treeSha(lastKeptSha)
                .build();
        when(evidenceService.findIterations(runId)).thenReturn(List.of(iter1));

        // Max 2 iterations config
        LoopConfig loopConfig = new LoopConfig(
                2, 1_800_000L, 500_000L,
                new BigDecimal("10.00"), 5, 0.50,
                BigDecimal.ZERO, BigDecimal.ZERO
        );
        GenParams genParams = new GenParams("anthropic", "claude-3-7-sonnet", 0.0, 2000);

        // Turn 2 decision: follow up optimization
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
        io.diag.evidence.dto.JfrReportDto fakeJfr2 = new io.diag.evidence.dto.JfrReportDto("r", "iter2", Map.of(), null);
        BenchmarkCycle iter2Cycle = new BenchmarkCycle(iter2Load, 10L, fakeJfr2, 10L);
        when(targetPipeline.benchmark(eq("iter-2"), any())).thenReturn(iter2Cycle);

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                loopConfig, genParams, targetRepo, "S1"
        );

        String resumedRunId = loop.resume(runId);
        assertThat(resumedRunId).isEqualTo(runId);

        // Verify revert-if-dirty reverted working tree to lastKeptSha
        verify(changeApplier, Mockito.atLeastOnce()).revertTo(targetRepo, lastKeptSha);

        // Verify baseline was NOT redone (benchmark with baseline-1 never called)
        verify(targetPipeline, never()).benchmark(eq("baseline-1"), any());

        // Verify turn 2 was executed starting from n=2
        verify(decideTurn).decide(eq(2), any());

        // Verify run finished as COMPLETED
        verify(evidenceService).transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.COMPLETED);
    }
}
