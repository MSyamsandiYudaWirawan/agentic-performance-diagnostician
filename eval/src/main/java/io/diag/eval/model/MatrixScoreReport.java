package io.diag.eval.model;

import java.util.List;
import java.util.Objects;

/**
 * Aggregated score report across the target matrix (Step 11 M0, scope §6.1, §6.2).
 */
public record MatrixScoreReport(
        int totalTargets,
        int accurateCount,
        double accuracyRate,
        int convergedCount,
        double convergenceRate,
        List<RunScore> runScores
) {
    public MatrixScoreReport {
        Objects.requireNonNull(runScores, "runScores must not be null");
    }

    /**
     * V1.0 PASS threshold (scope §6): accuracy >= 70% AND >= 2 of 4 targets converged.
     */
    public boolean passesV1Threshold() {
        return accuracyRate >= 0.70 && convergedCount >= 2;
    }
}
