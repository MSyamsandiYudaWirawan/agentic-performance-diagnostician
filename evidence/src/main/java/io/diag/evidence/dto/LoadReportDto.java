package io.diag.evidence.dto;

public record LoadReportDto(
        String repo, String dateUtc,
        double rps, long totalRequests,
        LatencyDto latency,
        double failRate, double checkPassRate,
        ThresholdsDto thresholds
) {
}

