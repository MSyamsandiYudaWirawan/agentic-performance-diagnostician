package io.diag.agent.loop;

import io.diag.agent.decision.DecisionDto;

/**
 * Seam for one LLM decide turn (§5.7, D2).
 * SpringAiDecideTurn is the real impl; FakeDecideTurn is the test double (M3).
 */
public interface DecideTurn {
    DecideResult decide(int n, DecideContext ctx);

    record DecideResult(
            DecisionDto decision,   // null on failure
            String error,
            boolean infraFailure,   // true = provider dead; false = model gave bad JSON
            long tokensIn,
            long tokensOut,
            String rawText
    ) {
        public DecideResult {
            if (tokensIn < 0)  throw new IllegalArgumentException("tokensIn must be >= 0, got " + tokensIn);
            if (tokensOut < 0) throw new IllegalArgumentException("tokensOut must be >= 0, got " + tokensOut);
        }
    }
}
