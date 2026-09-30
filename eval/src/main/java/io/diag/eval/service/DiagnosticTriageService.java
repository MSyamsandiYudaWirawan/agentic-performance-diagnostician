package io.diag.eval.service;

import io.diag.eval.model.DiagnosticTriage;

/**
 * Service contract for diagnosing failure stages in diagnostic runs (§6, §10.20, Step 12 M2).
 * Evaluates JFR quality, model accuracy, template selection, and keep-rule rejection.
 */
public interface DiagnosticTriageService {

    /**
     * Evaluates a run and produces an actionable triage finding.
     *
     * @param runId identifier of the run
     * @return diagnostic triage report
     */
    DiagnosticTriage analyze(String runId);
}
