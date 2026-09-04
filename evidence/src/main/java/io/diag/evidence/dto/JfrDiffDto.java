package io.diag.evidence.dto;

import java.util.Map;

public record JfrDiffDto(
        String baseLabel,
        Map<String, SignalDeltaDto> deltas   // per signal
) {}
