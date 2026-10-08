package io.diag.evidence.dto;

import java.util.List;
import java.util.Objects;

/**
 * Encapsulates the 3 baseline load reports and the baseline JFR report (label "baseline-3")
 * for a specific baseline run (Step 9/10 baseline caching and DB fallback).
 */
public record BaselineReportsDto(
        String runId,
        List<LoadReportDto> loadReports,
        JfrReportDto jfrReport
) {
    public BaselineReportsDto {
        Objects.requireNonNull(loadReports, "loadReports must not be null");
        Objects.requireNonNull(jfrReport, "jfrReport must not be null");
        if (loadReports.size() != 3) {
            throw new IllegalArgumentException("loadReports must contain exactly 3 baseline cycles, got " + loadReports.size());
        }
    }

    public BaselineReportsDto(List<LoadReportDto> loadReports, JfrReportDto jfrReport) {
        this(null, loadReports, jfrReport);
    }
}
