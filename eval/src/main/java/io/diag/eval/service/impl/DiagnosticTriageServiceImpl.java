package io.diag.eval.service.impl;

import io.diag.eval.model.DiagnosticTriage;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.DiagnosticTriageService;
import io.diag.eval.service.EvalScorer;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.JfrReportRepository;
import io.diag.evidence.repository.RunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * DB-backed implementation of DiagnosticTriageService (§6, §10.20, Step 12 M2, TigerStyle compliant).
 * Evaluates the triage hierarchy:
 * 1. JFR Quality -> 2. Model Diagnosis -> 3. Template Selection -> 4. Keep-Rule Rejection.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DiagnosticTriageServiceImpl implements DiagnosticTriageService {

    private final RunRepository runRepository;
    private final IterationRepository iterationRepository;
    private final JfrReportRepository jfrReportRepository;
    private final EvalScorer evalScorer;

    @Override
    public DiagnosticTriage analyze(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");

        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        RunScore score = evalScorer.scoreRun(runId);
        List<Iteration> iterations = iterationRepository.findByRunIdOrderByNAsc(runId);

        // 1. Success check: accurate diagnosis and converged beyond noise floor
        if (score.accurate() && score.converged()) {
            return new DiagnosticTriage(
                    runId,
                    score.targetId(),
                    DiagnosticTriage.STAGE_NONE,
                    "Run converged successfully beyond noise floor with accurate root-cause diagnosis.",
                    "No triage action required; verified against success criteria."
            );
        }

        // 2. Guardrail check: run aborted by safety caps
        if ("ABORTED".equalsIgnoreCase(run.getStatus())) {
            return new DiagnosticTriage(
                    runId,
                    score.targetId(),
                    DiagnosticTriage.STAGE_GUARDRAIL_EXCEEDED,
                    "Run aborted by safety guardrail cap (wall-clock, tokens, or cost).",
                    "Inspect loop configuration caps or optimize prompt token consumption."
            );
        }

        // 3. Triage Hierarchy Step 1: JFR Quality
        boolean jfrDegraded = isJfrDegraded(runId, iterations);
        if (!score.accurate() && jfrDegraded) {
            return new DiagnosticTriage(
                    runId,
                    score.targetId(),
                    DiagnosticTriage.STAGE_JFR_QUALITY,
                    "JFR report contained 0 signals or profiling recording was missing during baseline/iteration.",
                    "Verify JFR agent settings, dump-on-exit flags, and container bind mount permissions."
            );
        }

        // 4. Triage Hierarchy Step 2: Model Classification (Inaccurate Diagnosis)
        if (!score.accurate()) {
            return new DiagnosticTriage(
                    runId,
                    score.targetId(),
                    DiagnosticTriage.STAGE_MODEL_DIAGNOSIS,
                    String.format(Locale.ROOT, "Model diagnosed category %s, but ground truth is %s.",
                            score.diagnosedCategory(), score.groundTruthCategory()),
                    "Refine diagnostic system prompt instructions or switch to a higher-capability model."
            );
        }

        // 5. Triage Hierarchy Step 3 & 4: Template Selection & Keep-Rule Rejection
        boolean hasWasted = false;
        boolean hasReverted = false;
        for (Iteration it : iterations) {
            if ("WASTED".equalsIgnoreCase(it.getOutcome()) || "FAILED".equalsIgnoreCase(it.getOutcome())) {
                hasWasted = true;
            } else if ("REVERTED".equalsIgnoreCase(it.getOutcome())) {
                hasReverted = true;
            }
        }

        if (hasWasted) {
            return new DiagnosticTriage(
                    runId,
                    score.targetId(),
                    DiagnosticTriage.STAGE_TEMPLATE_SELECTION,
                    "Proposed fix template was unadmitted, rejected by validation, or failed smoke/tests.",
                    "Ensure fix template is admitted in FixTemplateRegistry with valid, tested parameters."
            );
        }

        if (hasReverted) {
            return new DiagnosticTriage(
                    runId,
                    score.targetId(),
                    DiagnosticTriage.STAGE_KEEP_RULE_REJECTED,
                    String.format(Locale.ROOT, "Applied fix was reverted because p95 delta (%.1f ms) did not exceed noise floor (%.1f ms).",
                            score.p95DeltaMs(), score.noiseFloorMs()),
                    "Tune template intensity or increase benchmark cycle duration to stabilize noise floor."
            );
        }

        // Fallback for non-converged runs
        return new DiagnosticTriage(
                runId,
                score.targetId(),
                DiagnosticTriage.STAGE_KEEP_RULE_REJECTED,
                "Run exhausted iteration budget without reaching convergence beyond baseline noise floor.",
                "Increase max iterations or check for secondary bottleneck masks."
        );
    }

    private boolean isJfrDegraded(String runId, List<Iteration> iterations) {
        if (iterations.isEmpty()) {
            Optional<JfrReport> baseJfr = jfrReportRepository.findByRunIdAndLabel(runId, "baseline-3");
            return baseJfr.isEmpty() || baseJfr.get().getPayload() == null ||
                    baseJfr.get().getPayload().signals() == null || baseJfr.get().getPayload().signals().isEmpty();
        }

        // Check if all iterations had missing or empty JFR signals
        boolean anyValidJfr = false;
        for (Iteration it : iterations) {
            if (it.getJfrReportId() != null) {
                Optional<JfrReport> repOpt = jfrReportRepository.findById(it.getJfrReportId());
                if (repOpt.isPresent() && repOpt.get().getPayload() != null &&
                        repOpt.get().getPayload().signals() != null && !repOpt.get().getPayload().signals().isEmpty()) {
                    anyValidJfr = true;
                    break;
                }
            }
        }
        return !anyValidJfr;
    }
}
