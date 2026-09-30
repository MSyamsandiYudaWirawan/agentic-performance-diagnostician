package io.diag.eval.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

/**
 * Immutable evaluation score for a single diagnostic run (Step 11 M0, scope §6.2).
 */
public record RunScore(
        String runId,
        String targetId,
        String status,
        String groundTruthCategory,
        String diagnosedCategory,
        boolean accurate,
        double baselineP95Ms,
        double finalP95Ms,
        double p95DeltaMs,
        double noiseFloorMs,
        boolean converged,
        double baselineRps,
        double finalRps,
        double rpsDelta,
        double noiseFloorRps,
        boolean rpsImproved,
        int iterationsUsed,
        long totalTokens,
        BigDecimal totalCostUsd,
        Instant startedAt,
        Instant finishedAt
) {
    public RunScore {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(groundTruthCategory, "groundTruthCategory must not be null");
        Objects.requireNonNull(diagnosedCategory, "diagnosedCategory must not be null");
        Objects.requireNonNull(totalCostUsd, "totalCostUsd must not be null");
    }
}
