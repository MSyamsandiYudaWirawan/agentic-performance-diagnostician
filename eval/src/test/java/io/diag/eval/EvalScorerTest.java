package io.diag.eval;

import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.impl.EvalScorerImpl;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.service.TargetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Step 11 Milestone 0 Verify Gate (EvalScorerTest).
 * Tests scoring logic, accuracy rules, convergence evaluation, and matrix aggregation.
 */
public class EvalScorerTest {

    private RunRepository runRepository;
    private IterationRepository iterationRepository;
    private TargetRepository targetRepository;
    private LoadReportRepository loadReportRepository;
    private TargetRegistry targetRegistry;
    private EvalScorerImpl scorer;

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(RunRepository.class);
        iterationRepository = Mockito.mock(IterationRepository.class);
        targetRepository = Mockito.mock(TargetRepository.class);
        loadReportRepository = Mockito.mock(LoadReportRepository.class);
        targetRegistry = Mockito.mock(TargetRegistry.class);

        scorer = new EvalScorerImpl(
                runRepository, iterationRepository, targetRepository,
                loadReportRepository, targetRegistry);
    }

    @Test
    void scoreRun_accurateAndConverged() {
        String runId = "run-001";
        Run run = Run.builder()
                .id(runId)
                .targetId("S1")
                .status("COMPLETED")
                .baselineP95Ms(2500.0)
                .noiseFloorMs(125.0)
                .baselineRps(189.0)
                .noiseFloorRps(9.5)
                .tokensIn(1000L)
                .tokensOut(500L)
                .costUsd(new BigDecimal("0.02"))
                .startedAt(Instant.now().minusSeconds(300))
                .finishedAt(Instant.now())
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target target = Target.builder()
                .id("S1")
                .name("Stock PetClinic")
                .groundTruthCategory("H5")
                .groundTruthFix("jar-unpack")
                .build();
        when(targetRepository.findById("S1")).thenReturn(Optional.of(target));

        Iteration iter1 = Iteration.builder()
                .id(101L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.9, "lock contention"))
                .outcome("KEPT")
                .loadReportId(501L)
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter1));

        LoadReport lr = LoadReport.builder()
                .id(501L)
                .runId(runId)
                .label("iter-1")
                .payload(new LoadReportDto("repo", "2026-09-30", 270.0, 1000L,
                        new LatencyDto(100.0, 80.0, 1800.0, 2200.0, 3000.0),
                        0.0, 1.0, null))
                .build();
        when(loadReportRepository.findById(501L)).thenReturn(Optional.of(lr));

        RunScore score = scorer.scoreRun(runId);

        assertThat(score.runId()).isEqualTo(runId);
        assertThat(score.targetId()).isEqualTo("S1");
        assertThat(score.groundTruthCategory()).isEqualTo("H5");
        assertThat(score.diagnosedCategory()).isEqualTo("H5");
        assertThat(score.accurate()).isTrue();
        assertThat(score.finalP95Ms()).isEqualTo(1800.0);
        assertThat(score.p95DeltaMs()).isEqualTo(700.0);
        assertThat(score.converged()).isTrue();
        assertThat(score.finalRps()).isEqualTo(270.0);
        assertThat(score.rpsImproved()).isTrue();
        assertThat(score.iterationsUsed()).isEqualTo(1);
        assertThat(score.totalTokens()).isEqualTo(1500L);
    }

    @Test
    void scoreRun_inaccurateWhenDiagnosisDiffers() {
        String runId = "run-002";
        Run run = Run.builder()
                .id(runId)
                .targetId("S2")
                .status("COMPLETED")
                .baselineP95Ms(2000.0)
                .noiseFloorMs(100.0)
                .baselineRps(150.0)
                .noiseFloorRps(8.0)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target target = Target.builder()
                .id("S2")
                .groundTruthCategory("H2") // S2 ground truth is H2 (pool starvation)
                .build();
        when(targetRepository.findById("S2")).thenReturn(Optional.of(target));

        Iteration iter1 = Iteration.builder()
                .id(102L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H3", 0.6, "suspected GC issue")) // Incorrectly diagnosed H3
                .outcome("KEPT")
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter1));

        RunScore score = scorer.scoreRun(runId);

        assertThat(score.accurate()).isFalse();
        assertThat(score.groundTruthCategory()).isEqualTo("H2");
        assertThat(score.diagnosedCategory()).isEqualTo("H3");
    }

    @Test
    void scoreRuns_computesMatrixAggregates() {
        String r1 = "r1";
        String r2 = "r2";

        Run run1 = Run.builder().id(r1).targetId("S1").status("COMPLETED").baselineP95Ms(2000.0).noiseFloorMs(100.0).build();
        Run run2 = Run.builder().id(r2).targetId("S2").status("COMPLETED").baselineP95Ms(2000.0).noiseFloorMs(100.0).build();
        when(runRepository.findById(r1)).thenReturn(Optional.of(run1));
        when(runRepository.findById(r2)).thenReturn(Optional.of(run2));

        when(targetRepository.findById("S1")).thenReturn(Optional.of(Target.builder().id("S1").groundTruthCategory("H5").build()));
        when(targetRepository.findById("S2")).thenReturn(Optional.of(Target.builder().id("S2").groundTruthCategory("H2").build()));

        // r1: accurate (H5 == H5) and converged
        Iteration i1 = Iteration.builder().id(1L).runId(r1).n(1).hypothesis(new HypothesisDto("H5", 0.9, "")).outcome("KEPT").loadReportId(10L).build();
        when(iterationRepository.findByRunIdOrderByNAsc(r1)).thenReturn(List.of(i1));
        when(loadReportRepository.findById(10L)).thenReturn(Optional.of(LoadReport.builder().payload(new LoadReportDto("repo", "date", 200.0, 100L, new LatencyDto(100.0, 80.0, 1500.0, 1800.0, 2000.0), 0.0, 1.0, null)).build()));

        // r2: inaccurate (H1 != H2)
        Iteration i2 = Iteration.builder().id(2L).runId(r2).n(1).hypothesis(new HypothesisDto("H1", 0.5, "")).outcome("REVERTED").build();
        when(iterationRepository.findByRunIdOrderByNAsc(r2)).thenReturn(List.of(i2));

        MatrixScoreReport report = scorer.scoreRuns(List.of(r1, r2));

        assertThat(report.totalTargets()).isEqualTo(2);
        assertThat(report.accurateCount()).isEqualTo(1);
        assertThat(report.accuracyRate()).isEqualTo(0.5);
        assertThat(report.convergedCount()).isEqualTo(1);
        assertThat(report.convergenceRate()).isEqualTo(0.5);
    }

    @Test
    void tigerStyle_failFastOnNulls() {
        assertThatThrownBy(() -> scorer.scoreRun(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> scorer.scoreRuns(null))
                .isInstanceOf(NullPointerException.class);
    }
}
