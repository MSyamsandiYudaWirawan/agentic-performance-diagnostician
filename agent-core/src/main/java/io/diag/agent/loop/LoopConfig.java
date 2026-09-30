package io.diag.agent.loop;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Guardrail caps and pricing for one agent run (§5.1).
 * Prices default to zero — cost guardrail is inert until billing data arrives (open-questions C13).
 * fromEnv(Map) is the testable entry point; fromEnv() delegates to System.getenv().
 */
public record LoopConfig(
        int maxIterations,
        long maxWallMs,
        long maxTokens,
        BigDecimal maxCostUsd,
        int toolCallBound,
        double keepP95RegressionBound,
        BigDecimal priceInPerMtok,
        BigDecimal priceOutPerMtok
) {
    public LoopConfig {
        Objects.requireNonNull(maxCostUsd,       "maxCostUsd must not be null");
        Objects.requireNonNull(priceInPerMtok,   "priceInPerMtok must not be null");
        Objects.requireNonNull(priceOutPerMtok,  "priceOutPerMtok must not be null");
        if (maxIterations <= 0)
            throw new IllegalArgumentException("maxIterations must be > 0, got " + maxIterations);
        if (maxWallMs <= 0)
            throw new IllegalArgumentException("maxWallMs must be > 0, got " + maxWallMs);
        if (maxTokens <= 0)
            throw new IllegalArgumentException("maxTokens must be > 0, got " + maxTokens);
        if (toolCallBound <= 0)
            throw new IllegalArgumentException("toolCallBound must be > 0, got " + toolCallBound);
        if (keepP95RegressionBound < 0.0)
            throw new IllegalArgumentException("keepP95RegressionBound must be >= 0, got " + keepP95RegressionBound);
        if (maxCostUsd.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("maxCostUsd must be >= 0, got " + maxCostUsd);
        if (priceInPerMtok.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("priceInPerMtok must be >= 0, got " + priceInPerMtok);
        if (priceOutPerMtok.compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("priceOutPerMtok must be >= 0, got " + priceOutPerMtok);
    }

    public static LoopConfig defaults() {
        return new LoopConfig(
                5,
                3_600_000L,
                500_000L,
                new BigDecimal("5.00"),
                10,
                0.50,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    public static LoopConfig fromEnv() {
        java.util.Map<String, String> merged = new java.util.HashMap<>(System.getenv());
        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith("diag.") || key.startsWith("DIAG_")) {
                String envKey = key.toUpperCase().replace('.', '_');
                merged.put(envKey, System.getProperty(key));
            }
        }
        return fromEnv(merged);
    }

    public static LoopConfig fromEnv(Map<String, String> env) {
        Objects.requireNonNull(env, "env must not be null");
        LoopConfig d = defaults();
        return new LoopConfig(
                parseInt(env,    "DIAG_MAX_ITERATIONS",   d.maxIterations),
                parseLong(env,   "DIAG_MAX_WALL_MS",      d.maxWallMs),
                parseLong(env,   "DIAG_MAX_TOKENS",       d.maxTokens),
                parseDecimal(env,"DIAG_MAX_COST_USD",     d.maxCostUsd),
                parseInt(env,    "DIAG_TOOL_BOUND",       d.toolCallBound),
                parseDouble(env, "DIAG_KEEP_P95_BOUND",   d.keepP95RegressionBound),
                parseDecimal(env,"DIAG_PRICE_IN_MTOK",    d.priceInPerMtok),
                parseDecimal(env,"DIAG_PRICE_OUT_MTOK",   d.priceOutPerMtok)
        );
    }

    private static int parseInt(Map<String, String> env, String key, int fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("env " + key + " is not a valid integer: " + v, e);
        }
    }

    private static long parseLong(Map<String, String> env, String key, long fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("env " + key + " is not a valid long: " + v, e);
        }
    }

    private static double parseDouble(Map<String, String> env, String key, double fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("env " + key + " is not a valid double: " + v, e);
        }
    }

    private static BigDecimal parseDecimal(Map<String, String> env, String key, BigDecimal fallback) {
        String v = env.get(key);
        if (v == null || v.isBlank()) return fallback;
        try {
            return new BigDecimal(v.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("env " + key + " is not a valid decimal: " + v, e);
        }
    }
}
