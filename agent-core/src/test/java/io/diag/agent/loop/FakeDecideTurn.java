package io.diag.agent.loop;

import io.diag.agent.decision.DecisionDto;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.function.BiFunction;

/**
 * Scriptable test double for DecideTurn (§10, M3).
 * Returns canned DecideResults in order or uses a scripted function.
 */
public final class FakeDecideTurn implements DecideTurn {

    private final Queue<DecideResult> cannedResults = new ArrayDeque<>();
    private final List<DecideContext> recordedContexts = new ArrayList<>();
    private BiFunction<Integer, DecideContext, DecideResult> responder;

    public FakeDecideTurn enqueue(DecideResult result) {
        Objects.requireNonNull(result, "result must not be null");
        cannedResults.add(result);
        return this;
    }

    public FakeDecideTurn setResponder(BiFunction<Integer, DecideContext, DecideResult> responder) {
        this.responder = Objects.requireNonNull(responder, "responder must not be null");
        return this;
    }

    @Override
    public DecideResult decide(int n, DecideContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        recordedContexts.add(ctx);

        if (!cannedResults.isEmpty()) {
            return cannedResults.poll();
        }
        if (responder != null) {
            return responder.apply(n, ctx);
        }
        throw new IllegalStateException("FakeDecideTurn: no canned result or responder set for iteration " + n);
    }

    public List<DecideContext> recordedContexts() {
        return new ArrayList<>(recordedContexts);
    }

    // -------------------------------------------------------------------------
    // Convenience builders
    // -------------------------------------------------------------------------

    public static DecideResult valid(DecisionDto decision) {
        return new DecideResult(decision, null, false, 1200L, 350L, "fake-json");
    }

    public static DecideResult invalid(String error) {
        return new DecideResult(null, error, false, 800L, 150L, "malformed-json");
    }

    public static DecideResult infra(String error) {
        return new DecideResult(null, error, true, 0L, 0L, null);
    }
}
