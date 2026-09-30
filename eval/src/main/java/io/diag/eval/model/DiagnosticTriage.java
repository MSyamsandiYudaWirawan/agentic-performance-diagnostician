package io.diag.eval.model;

import java.util.Objects;

/**
 * Diagnostic triage assessment for runs that failed to accurately diagnose or converge (§6, §10.20, Step 12 M2).
 * Implements the deterministic triage hierarchy:
 * 1. JFR Quality -> 2. Model Diagnosis -> 3. Template Selection -> 4. Keep-Rule Rejection.
 */
public record DiagnosticTriage(
        String runId,
        String targetId,
        String failureStage,
        String detail,
        String recommendedAction
) {
    public static final String STAGE_NONE = "NONE";
    public static final String STAGE_GUARDRAIL_EXCEEDED = "GUARDRAIL_EXCEEDED";
    public static final String STAGE_JFR_QUALITY = "JFR_QUALITY";
    public static final String STAGE_MODEL_DIAGNOSIS = "MODEL_DIAGNOSIS";
    public static final String STAGE_TEMPLATE_SELECTION = "TEMPLATE_SELECTION";
    public static final String STAGE_KEEP_RULE_REJECTED = "KEEP_RULE_REJECTED";

    public DiagnosticTriage {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(failureStage, "failureStage must not be null");
        Objects.requireNonNull(detail, "detail must not be null");
        Objects.requireNonNull(recommendedAction, "recommendedAction must not be null");
    }

    public boolean isResolved() {
        return STAGE_NONE.equals(failureStage);
    }
}
