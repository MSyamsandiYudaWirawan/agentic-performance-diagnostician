package io.diag.agent.loop;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;

import java.util.List;
import java.util.Objects;

/**
 * Cache entry holding baseline metrics and artifacts for a specific profile_hash (Step 10 D2, §10.24).
 */
public record BaselineCacheEntry(
        String profileHash,
        double baselineP95Ms,
        double noiseFloorMs,
        double baselineRps,
        double noiseFloorRps,
        List<LoadReportDto> loadReports,
        JfrReportDto jfrReport
) {
    public BaselineCacheEntry {
        Objects.requireNonNull(profileHash, "profileHash must not be null");
        Objects.requireNonNull(loadReports, "loadReports must not be null");
        if (loadReports.size() != 3) {
            throw new IllegalArgumentException("loadReports must contain exactly 3 baseline cycles, got " + loadReports.size());
        }
    }
}
