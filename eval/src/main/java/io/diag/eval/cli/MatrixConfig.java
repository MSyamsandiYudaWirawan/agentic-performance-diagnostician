package io.diag.eval.cli;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.LoopConfig;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable configuration for Matrix CLI and Matrix Runner (§6, Step 12 M0, TigerStyle compliant).
 * Reads from command-line arguments and environment variables with safe, reproducible defaults.
 */
public record MatrixConfig(
        List<String> targetIds,
        Path reportsDir,
        LoopConfig loopConfig,
        GenParams genParams,
        boolean dryRun
) {
    public static final List<String> DEFAULT_TARGETS = List.of("S1", "S2", "S3", "S4");
    public static final Path DEFAULT_REPORTS_DIR = Path.of("target", "reports");
    public static final int DEFAULT_MAX_ITERATIONS = 5;
    public static final long DEFAULT_MAX_WALL_MS = 1_800_000L; // 30 minutes
    public static final long DEFAULT_MAX_TOKENS = 500_000L;
    public static final BigDecimal DEFAULT_MAX_COST = new BigDecimal("10.00");
    public static final String DEFAULT_PROVIDER = "anthropic";
    public static final String DEFAULT_MODEL = "claude-3-7-sonnet";

    public MatrixConfig {
        Objects.requireNonNull(targetIds, "targetIds must not be null");
        Objects.requireNonNull(reportsDir, "reportsDir must not be null");
        Objects.requireNonNull(loopConfig, "loopConfig must not be null");
        Objects.requireNonNull(genParams, "genParams must not be null");
    }

    /**
     * Factory parsing system environment variables and CLI arguments.
     */
    public static MatrixConfig parse(String[] args) {
        return parse(args, System.getenv());
    }

    /**
     * Testable factory accepting explicit environment map and argument array.
     */
    public static MatrixConfig parse(String[] args, Map<String, String> env) {
        String[] safeArgs = args != null ? args : new String[0];
        Map<String, String> safeEnv = env != null ? env : Map.of();

        List<String> targets = resolveTargets(safeArgs, safeEnv);
        Path reportsDir = resolveReportsDir(safeArgs, safeEnv);
        boolean dryRun = resolveDryRun(safeArgs, safeEnv);
        LoopConfig loopConfig = resolveLoopConfig(safeArgs, safeEnv);
        GenParams genParams = resolveGenParams(safeArgs, safeEnv);

        return new MatrixConfig(targets, reportsDir, loopConfig, genParams, dryRun);
    }

    private static List<String> resolveTargets(String[] args, Map<String, String> env) {
        for (String arg : args) {
            if (arg.startsWith("--targets=")) {
                String val = arg.substring("--targets=".length()).trim();
                return parseCommaList(val);
            }
        }
        String envVal = env.get("DIAG_TARGETS");
        if (envVal != null && !envVal.isBlank()) {
            return parseCommaList(envVal.trim());
        }
        return DEFAULT_TARGETS;
    }

    private static Path resolveReportsDir(String[] args, Map<String, String> env) {
        for (String arg : args) {
            if (arg.startsWith("--reports-dir=")) {
                return Path.of(arg.substring("--reports-dir=".length()).trim());
            }
        }
        String envVal = env.get("DIAG_REPORTS_DIR");
        if (envVal != null && !envVal.isBlank()) {
            return Path.of(envVal.trim());
        }
        return DEFAULT_REPORTS_DIR;
    }

    private static boolean resolveDryRun(String[] args, Map<String, String> env) {
        for (String arg : args) {
            if (arg.equals("--dry-run") || arg.equalsIgnoreCase("--dry-run=true")) {
                return true;
            }
            if (arg.equalsIgnoreCase("--dry-run=false")) {
                return false;
            }
        }
        String envVal = env.get("DIAG_DRY_RUN");
        return "true".equalsIgnoreCase(envVal);
    }

    private static LoopConfig resolveLoopConfig(String[] args, Map<String, String> env) {
        int maxIters = DEFAULT_MAX_ITERATIONS;
        long maxWallMs = DEFAULT_MAX_WALL_MS;
        long maxTokens = DEFAULT_MAX_TOKENS;
        BigDecimal maxCost = DEFAULT_MAX_COST;

        for (String arg : args) {
            if (arg.startsWith("--max-iterations=")) {
                maxIters = Integer.parseInt(arg.substring("--max-iterations=".length()).trim());
            } else if (arg.startsWith("--max-wall-ms=")) {
                maxWallMs = Long.parseLong(arg.substring("--max-wall-ms=".length()).trim());
            } else if (arg.startsWith("--max-tokens=")) {
                maxTokens = Long.parseLong(arg.substring("--max-tokens=".length()).trim());
            } else if (arg.startsWith("--max-cost=")) {
                maxCost = new BigDecimal(arg.substring("--max-cost=".length()).trim());
            }
        }

        if (env.containsKey("DIAG_MAX_ITERATIONS")) {
            maxIters = Integer.parseInt(env.get("DIAG_MAX_ITERATIONS").trim());
        }
        if (env.containsKey("DIAG_MAX_WALL_MS")) {
            maxWallMs = Long.parseLong(env.get("DIAG_MAX_WALL_MS").trim());
        }
        if (env.containsKey("DIAG_MAX_TOKENS")) {
            maxTokens = Long.parseLong(env.get("DIAG_MAX_TOKENS").trim());
        }
        if (env.containsKey("DIAG_MAX_COST")) {
            maxCost = new BigDecimal(env.get("DIAG_MAX_COST").trim());
        }

        return new LoopConfig(
                maxIters,
                maxWallMs,
                maxTokens,
                maxCost,
                5,
                0.50,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    private static GenParams resolveGenParams(String[] args, Map<String, String> env) {
        String provider = DEFAULT_PROVIDER;
        String model = DEFAULT_MODEL;

        for (String arg : args) {
            if (arg.startsWith("--provider=")) {
                provider = arg.substring("--provider=".length()).trim();
            } else if (arg.startsWith("--model=")) {
                model = arg.substring("--model=".length()).trim();
            }
        }

        if (env.containsKey("DIAG_PROVIDER")) {
            provider = env.get("DIAG_PROVIDER").trim();
        }
        if (env.containsKey("DIAG_MODEL")) {
            model = env.get("DIAG_MODEL").trim();
        }

        return new GenParams(provider, model, 0.0, 2000);
    }

    private static List<String> parseCommaList(String val) {
        String[] parts = val.split(",");
        List<String> list = new ArrayList<>(parts.length);
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) {
                list.add(trimmed);
            }
        }
        return list;
    }
}
