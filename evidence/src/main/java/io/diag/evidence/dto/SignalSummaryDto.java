package io.diag.evidence.dto;

import java.util.List;

public record SignalSummaryDto(
        long count, Double p50ms, Double p95ms, Double p99ms, Double maxMs,
        String severity,                  // OK|WARN|CRITICAL
        List<String> topFrames
) {
}
