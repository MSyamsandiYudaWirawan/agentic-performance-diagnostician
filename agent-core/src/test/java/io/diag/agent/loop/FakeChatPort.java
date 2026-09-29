package io.diag.agent.loop;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Scripted test double for ChatPort.
 * Each call pops the next Response from the queue.
 * A Response can be a text reply or an exception to throw — covering both
 * the model-fault and infra-fault tracks (D3).
 */
public class FakeChatPort implements ChatPort {

    public sealed interface Response permits Response.Text, Response.Throw {
        record Text(String text, long tokensIn, long tokensOut) implements Response {}
        record Throw(RuntimeException ex) implements Response {}
    }

    private final Deque<Response> queue = new ArrayDeque<>();
    private int callCount = 0;
    private String lastUserPrompt;
    private List<Object> lastToolBeans;

    public FakeChatPort enqueue(String text, long tokensIn, long tokensOut) {
        queue.add(new Response.Text(text, tokensIn, tokensOut));
        return this;
    }

    public FakeChatPort enqueueThrow(RuntimeException ex) {
        queue.add(new Response.Throw(ex));
        return this;
    }

    public int callCount() {
        return callCount;
    }

    public String lastUserPrompt() {
        return lastUserPrompt;
    }

    public List<Object> lastToolBeans() {
        return lastToolBeans;
    }

    @Override
    public ChatResult chat(String system, String user, List<Object> toolBeans) {
        callCount++;
        this.lastUserPrompt = user;
        this.lastToolBeans = toolBeans;
        if (queue.isEmpty()) {
            throw new IllegalStateException("FakeChatPort: no more scripted responses (call " + callCount + ")");
        }
        Response next = queue.poll();
        if (next instanceof Response.Text t) {
            return new ChatResult(t.text(), t.tokensIn(), t.tokensOut());
        }
        if (next instanceof Response.Throw th) {
            throw th.ex();
        }
        throw new IllegalStateException("unhandled response type: " + next);
    }
}
