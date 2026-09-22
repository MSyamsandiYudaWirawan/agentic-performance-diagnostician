package io.diag.agent.config;

import java.util.Map;

/**
 * Generation parameters recorded on the run row as gen_params (§10.20/§10.34).
 * Exposed as a Map for JSONB persistence — attribution beyond prompt_hash/model.
 */
public record GenParams(String provider, String model, double temperature, int maxTokens) {
    public Map<String, Object> toMap() {
        return Map.of(
                "provider", provider,
                "model", model,
                "temperature", temperature,
                "maxTokens", maxTokens
        );
    }
}
