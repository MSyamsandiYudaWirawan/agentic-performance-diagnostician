package io.diag.agent.advisor;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Deterministic, 100% offline Spring AI {@link ChatModel} test double for verifying
 * the {@code io.diag.agent.advisor} CallAdvisor onion pipeline.
 */
public class FakeAdvisorChatModel implements ChatModel {

    public static final String VALID_DECISION_JSON = """
            {
              "hypothesis": {
                "category": "H5",
                "confidence": 0.9,
                "rationale": "JavaMonitorEnter lock contention on JarFile"
              },
              "prediction": {
                "metricToImprove": "p95",
                "direction": "improve",
                "mechanismSignalToEliminate": "JavaMonitorEnter"
              },
              "ledger": {
                "category": "H5",
                "direction": "strengthen",
                "reason": "JarFile monitor lock is primary bottleneck"
              },
              "change": {
                "kind": "template",
                "template": "jar-unpack",
                "params": {}
              }
            }
            """;

    private final Deque<String> scriptedResponses = new ArrayDeque<>();
    private final Deque<RuntimeException> scriptedFailures = new ArrayDeque<>();
    private final List<Prompt> capturedPrompts = new CopyOnWriteArrayList<>();
    private Integer promptTokens = 1_000;
    private Integer completionTokens = 500;

    public FakeAdvisorChatModel enqueueResponse(String responseText) {
        scriptedResponses.add(Objects.requireNonNull(responseText));
        return this;
    }

    public FakeAdvisorChatModel enqueueFailure(RuntimeException failure) {
        scriptedFailures.add(Objects.requireNonNull(failure));
        return this;
    }

    public FakeAdvisorChatModel setTokenUsage(Integer promptTokens, Integer completionTokens) {
        this.promptTokens = promptTokens;
        this.completionTokens = completionTokens;
        return this;
    }

    public int getCallCount() {
        return capturedPrompts.size();
    }

    public List<Prompt> getCapturedPrompts() {
        return Collections.unmodifiableList(capturedPrompts);
    }

    public Prompt getLastPrompt() {
        return capturedPrompts.isEmpty() ? null : capturedPrompts.get(capturedPrompts.size() - 1);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        capturedPrompts.add(prompt);

        RuntimeException failure = scriptedFailures.poll();
        if (failure != null) {
            throw failure;
        }

        String text = scriptedResponses.poll();
        if (text == null) {
            text = VALID_DECISION_JSON;
        }

        DefaultUsage usage = new DefaultUsage(promptTokens, completionTokens);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder().usage(usage).build();
        Generation generation = new Generation(new AssistantMessage(text));
        return new ChatResponse(List.of(generation), metadata);
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("Streaming not used in AdvisorOnionTest");
    }

    @Override
    @Deprecated
    public ChatOptions getDefaultOptions() {
        return null;
    }
}
