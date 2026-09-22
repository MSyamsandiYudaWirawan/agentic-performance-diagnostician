package io.diag.agent.decision;

import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.PredictionDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Structured decision schema (§10.21). Assembled from existing evidence DTOs
 * plus the new LedgerUpdateDto. Validated before any apply — invalid → error
 * envelope back to model, one retry, then WASTED (step 9 loop policy).
 *
 * prediction is required (§10.21): it is the falsifiable claim keep-rule v2b
 * verifies and a scored eval dimension. The step-8 JSON example in build-steps
 * omits it — that is a doc gap, not the contract.
 */
@ValidDecision
public record DecisionDto(
        @NotNull @Valid HypothesisDto hypothesis,
        @NotNull @Valid PredictionDto prediction,
        @NotNull @Valid LedgerUpdateDto ledger,
        @NotNull @Valid ChangeDto change
) {
}
