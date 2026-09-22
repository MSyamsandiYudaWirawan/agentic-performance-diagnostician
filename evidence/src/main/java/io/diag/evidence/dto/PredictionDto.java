package io.diag.evidence.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record PredictionDto(
        @NotBlank(message = "metricToImprove must not be blank")
        String metricToImprove,

        @Pattern(regexp = "improve|degrade", message = "direction must be improve or degrade")
        String direction,

        @NotBlank(message = "mechanismSignalToEliminate must not be blank")
        String mechanismSignalToEliminate
) {}
