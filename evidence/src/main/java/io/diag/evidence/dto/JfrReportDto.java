package io.diag.evidence.dto;

import java.util.Map;

public record JfrReportDto(
        String runId, String label,
        Map<String, SignalSummaryDto> signals,   // keyed by event type e.g. "JavaMonitorEnter"
        JfrDiffDto diff                          // null on first recording of a run
) {
}
