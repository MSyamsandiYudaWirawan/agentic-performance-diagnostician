package io.diag.evidence.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record HypothesisDto(
        @Pattern(regexp = "H[1-7]", message = "category must be H1–H7")
        String category,

        @DecimalMin(value = "0.0", message = "confidence must be >= 0")
        @DecimalMax(value = "1.0", message = "confidence must be <= 1")
        double confidence,

        @NotBlank(message = "rationale must not be blank")
        String rationale
) {}
