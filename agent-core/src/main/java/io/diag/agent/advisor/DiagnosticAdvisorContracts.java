package io.diag.agent.advisor;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Shared contracts, context keys, exceptions, and snapshots for the
 * Diagnostic CallAdvisor Governance & Resilience Onion Pipeline.
 */
public final class DiagnosticAdvisorContracts {

    private DiagnosticAdvisorContracts() {}

    /**
     * Standard advisor ordering constants (outermost = lowest number, innermost = highest number).
     */
    public static final class Orders {
        public static final int SECURITY_SANITIZER  = 10;
        public static final int SCHEMA_SELF_HEALING = 20;
        public static final int TOKEN_BUDGET        = 30;
        public static final int TELEMETRY_AND_AUDIT = 40;
        public static final int INFRA_RETRY         = 50;

        private Orders() {}
    }

    /**
     * Context keys passed via {@code ChatClientRequest.context()} and {@code ChatClientResponse.context()}.
     */
    public static final class ContextKeys {
        public static final String RUN_ID             = "diag.runId";
        public static final String ITERATION          = "diag.iteration";
        public static final String ATTEMPT            = "diag.attempt";
        public static final String VALIDATED_DECISION = "diag.validatedDecision";
        public static final String VALIDATION_ERROR   = "diag.validationError";
        public static final String SCHEMA_HEALED      = "diag.schemaHealed";
        public static final String LATENCY_MS         = "diag.latencyMs";
        public static final String CALL_COST_USD      = "diag.callCostUsd";

        private ContextKeys() {}
    }

    /**
     * Pluggable sleep strategy for {@link InfraRetryAdvisor}.
     * Production uses {@code Thread::sleep}; unit tests inject a no-op or recording sleeper.
     */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    /**
     * Thrown by {@link SecuritySanitizerAdvisor} when a prompt contains forbidden patterns
     * or unauthorized host filesystem paths.
     */
    public static class SecurityPolicyViolationException extends RuntimeException {
        public SecurityPolicyViolationException(String message) {
            super(message);
        }
    }

    /**
     * Thrown by {@link TokenBudgetAdvisor} when cumulative tokens or USD cost exceed
     * the configured {@code LoopConfig} ceiling before or after a call.
     */
    public static class BudgetExceededException extends RuntimeException {
        public BudgetExceededException(String message) {
            super(message);
        }
    }

    /**
     * Thrown by {@link InfraRetryAdvisor} when all Track-1 provider retry attempts are exhausted.
     */
    public static class InfraRetryExhaustedException extends RuntimeException {
        public InfraRetryExhaustedException(String message, Throwable cause) {
            super(message, cause);
        }

        public InfraRetryExhaustedException(String message) {
            super(message);
        }
    }

    /**
     * Immutable snapshot of cumulative token usage and USD spend for a diagnostic run.
     */
    public record TokenBudgetSnapshot(
            long tokensIn,
            long tokensOut,
            long totalTokens,
            BigDecimal costUsd,
            int callCount
    ) {
        public TokenBudgetSnapshot {
            Objects.requireNonNull(costUsd, "costUsd must not be null");
        }
    }
}
