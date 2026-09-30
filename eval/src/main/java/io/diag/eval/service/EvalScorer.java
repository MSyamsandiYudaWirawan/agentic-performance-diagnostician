package io.diag.eval.service;

import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RunScore;

import java.util.List;

/**
 * Evaluates and scores diagnostic runs directly from Evidence DB (Step 11 M0, scope §6.2).
 */
public interface EvalScorer {

    RunScore scoreRun(String runId);

    MatrixScoreReport scoreRuns(List<String> runIds);
}
