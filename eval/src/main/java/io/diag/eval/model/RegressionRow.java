package io.diag.eval.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Aggregated metrics row for a specific (RegressionKey, targetId) cell (Step 11 M1, scope §10.20).
 */
public record RegressionRow(
        RegressionKey key,
        String targetId,
        int runCount,
        double accuracyRate,
        double convergenceRate,
        double meanP95DeltaMs,
        double meanRpsDelta,
        double meanIterations,
        long meanTokens,
        BigDecimal meanCostUsd
) {
    public RegressionRow {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(meanCostUsd, "meanCostUsd must not be null");
    }
}
