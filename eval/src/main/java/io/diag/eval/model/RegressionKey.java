package io.diag.eval.model;

import java.util.Objects;

/**
 * Composite key identifying an attribution configuration for regression analysis (Step 11 M1, scope §10.20).
 */
public record RegressionKey(
        String promptHash,
        String model,
        String aggregatorVersion
) {
    public RegressionKey {
        Objects.requireNonNull(promptHash, "promptHash must not be null");
        Objects.requireNonNull(model, "model must not be null");
        Objects.requireNonNull(aggregatorVersion, "aggregatorVersion must not be null");
    }
}
