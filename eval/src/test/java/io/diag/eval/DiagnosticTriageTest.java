package io.diag.eval;

import io.diag.eval.model.DiagnosticTriage;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.impl.DiagnosticTriageServiceImpl;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.JfrReportRepository;
import io.diag.evidence.repository.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Step 12 Milestone 2 Verify Gate (DiagnosticTriageTest).
 * Tests failure diagnosis following the triage hierarchy (§6, §10.20):
 * JFR Quality -> Model Classification -> Template Selection -> Keep Rule Rejection.
 */
public class DiagnosticTriageTest {

    private RunRepository runRepository;
    private IterationRepository iterationRepository;
    private JfrReportRepository jfrReportRepository;
    private EvalScorer evalScorer;
    private DiagnosticTriageServiceImpl triageService;

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(RunRepository.class);
        iterationRepository = Mockito.mock(IterationRepository.class);
        jfrReportRepository = Mockito.mock(JfrReportRepository.class);
        evalScorer = Mockito.mock(EvalScorer.class);

        triageService = new DiagnosticTriageServiceImpl(
                runRepository, iterationRepository, jfrReportRepository, evalScorer
        );
    }

    @Test
    void analyze_whenAccurateAndConverged_returnsNone() {
        String runId = "run-success";
        Run run = Run.builder().id(runId).targetId("S1").status("COMPLETED").build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S1", "COMPLETED", "H5", "H5", true,
                180.0, 120.0, 60.0, 15.0, true,
                200.0, 250.0, 50.0, 10.0, true,
                1, 20000L, BigDecimal.ZERO, Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of());

        DiagnosticTriage triage = triageService.analyze(runId);
        assertThat(triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_NONE);
        assertThat(triage.isResolved()).isTrue();
    }

    @Test
    void analyze_whenAborted_returnsGuardrailExceeded() {
        String runId = "run-aborted";
        Run run = Run.builder().id(runId).targetId("S1").status("ABORTED").build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S1", "ABORTED", "H5", "H5", true,
                180.0, 180.0, 0.0, 15.0, false,
                200.0, 200.0, 0.0, 10.0, false,
                1, 500000L, new BigDecimal("10.00"), Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);

        DiagnosticTriage triage = triageService.analyze(runId);
        assertThat(triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_GUARDRAIL_EXCEEDED);
        assertThat(triage.detail()).contains("guardrail cap");
    }

    @Test
    void analyze_whenJfrEmpty_returnsJfrQuality() {
        String runId = "run-no-jfr";
        Run run = Run.builder().id(runId).targetId("S1").status("COMPLETED").build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S1", "COMPLETED", "H5", "H1", false,
                180.0, 180.0, 0.0, 15.0, false,
                200.0, 200.0, 0.0, 10.0, false,
                1, 10000L, BigDecimal.ZERO, Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);

        // Baseline JFR has no signals
        JfrReport emptyJfr = JfrReport.builder()
                .id(1L)
                .runId(runId)
                .payload(new JfrReportDto(runId, "baseline-3", Map.of(), null))
                .build();
        when(jfrReportRepository.findByRunIdAndLabel(runId, "baseline-3")).thenReturn(Optional.of(emptyJfr));
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of());

        DiagnosticTriage triage = triageService.analyze(runId);
        assertThat(triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_JFR_QUALITY);
        assertThat(triage.detail()).contains("0 signals");
    }

    @Test
    void analyze_whenModelPickedWrongHypothesis_returnsModelDiagnosis() {
        String runId = "run-wrong-hypo";
        Run run = Run.builder().id(runId).targetId("S3").status("COMPLETED").build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S3", "COMPLETED", "H3", "H1", false,
                200.0, 200.0, 0.0, 15.0, false,
                150.0, 150.0, 0.0, 10.0, false,
                1, 10000L, BigDecimal.ZERO, Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);

        // Valid JFR with signals
        SignalSummaryDto sig = new SignalSummaryDto(100L, 10.0, 50.0, 100.0, 200.0, "CRITICAL", List.of());
        JfrReport validJfr = JfrReport.builder()
                .id(5L)
                .runId(runId)
                .payload(new JfrReportDto(runId, "iter-1", Map.of("JavaMonitorEnter", sig), null))
                .build();
        when(jfrReportRepository.findById(5L)).thenReturn(Optional.of(validJfr));

        Iteration iter = Iteration.builder().id(1L).runId(runId).n(1).jfrReportId(5L).outcome("REVERTED").build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        DiagnosticTriage triage = triageService.analyze(runId);
        assertThat(triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_MODEL_DIAGNOSIS);
        assertThat(triage.detail()).contains("Model diagnosed category H1, but ground truth is H3");
    }

    @Test
    void analyze_whenTemplateWasted_returnsTemplateSelection() {
        String runId = "run-wasted";
        Run run = Run.builder().id(runId).targetId("S2").status("COMPLETED").build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S2", "COMPLETED", "H2", "H2", true,
                300.0, 300.0, 0.0, 20.0, false,
                100.0, 100.0, 0.0, 10.0, false,
                1, 10000L, BigDecimal.ZERO, Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);

        Iteration iter = Iteration.builder().id(1L).runId(runId).n(1).outcome("WASTED").build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        DiagnosticTriage triage = triageService.analyze(runId);
        assertThat(triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_TEMPLATE_SELECTION);
        assertThat(triage.detail()).contains("unadmitted, rejected by validation");
    }

    @Test
    void analyze_whenChangeReverted_returnsKeepRuleRejected() {
        String runId = "run-reverted";
        Run run = Run.builder().id(runId).targetId("S1").status("COMPLETED").build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S1", "COMPLETED", "H5", "H5", true,
                180.0, 175.0, 5.0, 15.0, false,
                200.0, 202.0, 2.0, 10.0, false,
                1, 10000L, BigDecimal.ZERO, Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);

        Iteration iter = Iteration.builder().id(1L).runId(runId).n(1).outcome("REVERTED").build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        DiagnosticTriage triage = triageService.analyze(runId);
        assertThat(triage.failureStage()).isEqualTo(DiagnosticTriage.STAGE_KEEP_RULE_REJECTED);
        assertThat(triage.detail()).contains("did not exceed noise floor");
    }
}
