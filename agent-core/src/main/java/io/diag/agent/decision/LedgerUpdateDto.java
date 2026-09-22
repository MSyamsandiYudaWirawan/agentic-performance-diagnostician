package io.diag.agent.decision;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * Per-iteration ledger update — new DTO for step 8 (§10.21/§10.22).
 * HypothesisDto/PredictionDto/ChangeDto already exist in evidence module.
 */
public record LedgerUpdateDto(
        @Pattern(regexp = "H[1-7]", message = "category must be H1–H7")
        String category,

        @Pattern(regexp = "strengthen|weaken", message = "direction must be strengthen or weaken")
        String direction,

        @NotBlank(message = "reason must not be blank")
        String reason
) {
}
