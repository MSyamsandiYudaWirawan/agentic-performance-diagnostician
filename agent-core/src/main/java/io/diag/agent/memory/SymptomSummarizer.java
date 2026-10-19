package io.diag.agent.memory;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;

public interface SymptomSummarizer {
    /**
     * Produces a compact, deterministic summary string (strictly <= 400 characters).
     *
     * Format:
     * "Load: <rps> RPS, p95 <p95>ms (<failRate>% errors). Hotspots: <Signal1> (<severity>, count=<N>, frames=[<f1>, <f2>]); <Signal2> (...)"
     *
     * Selection rules:
     * 1. Load: include raw RPS, p95 (ms rounded to integer), and error rate.
     * 2. JFR: Filter signals where severity is CRITICAL or CONCERNING. If none, take top-2 by count.
     * 3. Top frames: include up to 2 class/method frame strings per critical signal.
     * 4. Invariant: resulting string must not exceed 400 characters (truncated deterministically if necessary).
     */
    String summarize(LoadReportDto load, JfrReportDto jfr);
}