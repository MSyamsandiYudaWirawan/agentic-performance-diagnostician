package io.diag.runner.config;

/**
 * Severity cutoffs ported from REF jfr-diagnose.sh (env-overridable).
 * Four tiers: HEALTHY / MODERATE / CONCERNING / CRITICAL.
 * lock/io/park thresholds are p95 in ms; gc is p99 in ms; counts are raw event counts.
 */
public record Thresholds(
        // lock p95 ms: ≤1 HEALTHY, ≤5 MODERATE, ≤20 CONCERNING, >20 CRITICAL
        double lockHealthyMs,
        double lockModerateMs,
        double lockConcerningMs,
        // socket read p95 ms
        double ioHealthyMs,
        double ioModerateMs,
        double ioConcerningMs,
        // gc p99 ms
        double gcHealthyMs,
        double gcModerateMs,
        double gcConcerningMs,
        // park p95 ms
        double parkHealthyMs,
        double parkModerateMs,
        double parkConcerningMs,
        // count thresholds (for hypothesis confidence, not severity)
        long ioCount,
        long parkCount,
        long lockCount,
        long allocCount,
        long exCount
) {
    public static Thresholds fromEnv() {
        return new Thresholds(
                dbl("SEV_LOCK_HEALTHY_MS",    1),
                dbl("SEV_LOCK_MODERATE_MS",   5),
                dbl("SEV_LOCK_CONCERNING_MS", 20),
                dbl("SEV_IO_HEALTHY_MS",      1),
                dbl("SEV_IO_MODERATE_MS",     5),
                dbl("SEV_IO_CONCERNING_MS",   20),
                dbl("SEV_GC_HEALTHY_MS",      10),
                dbl("SEV_GC_MODERATE_MS",     50),
                dbl("SEV_GC_CONCERNING_MS",   200),
                dbl("SEV_PARK_HEALTHY_MS",    1),
                dbl("SEV_PARK_MODERATE_MS",   10),
                dbl("SEV_PARK_CONCERNING_MS", 50),
                lng("THRESH_IO_COUNT",    500),
                lng("THRESH_PARK_COUNT",  5000),
                lng("THRESH_LOCK_COUNT",  100),
                lng("THRESH_ALLOC_COUNT", 20000),
                lng("THRESH_EX_COUNT",    100)
        );
    }

    public String lockSeverity(double p95ms) { return severity(p95ms, lockHealthyMs, lockModerateMs, lockConcerningMs); }
    public String ioSeverity(double p95ms)   { return severity(p95ms, ioHealthyMs,   ioModerateMs,   ioConcerningMs);   }
    public String gcSeverity(double p99ms)   { return severity(p99ms, gcHealthyMs,   gcModerateMs,   gcConcerningMs);   }
    public String parkSeverity(double p95ms) { return severity(p95ms, parkHealthyMs, parkModerateMs, parkConcerningMs); }

    private static String severity(double val, double h, double m, double c) {
        if (val <= h) return "HEALTHY";
        if (val <= m) return "MODERATE";
        if (val <= c) return "CONCERNING";
        return "CRITICAL";
    }

    private static double dbl(String env, double def) {
        String v = System.getenv(env);
        return v != null ? Double.parseDouble(v) : def;
    }
    private static long lng(String env, long def) {
        String v = System.getenv(env);
        return v != null ? Long.parseLong(v) : def;
    }
}
