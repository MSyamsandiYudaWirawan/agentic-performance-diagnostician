package io.diag.agent.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * Thin adapter over Spring AI's ChatClient (§5.4).
 * Accessor chain verified against spring-ai 2.0.1 jars (see D7/§5.4 notes):
 *   .call().chatResponse() → getResult().getOutput().getText()
 *   getMetadata().getUsage() → getPromptTokens() / getCompletionTokens() (Integer, null-safe to 0)
 *
 * Exceptions from the provider (timeout, 429, IO) are NOT caught here —
 * they propagate to SpringAiDecideTurn which owns the retry/backoff policy (D3).
 * This class has one job: call the model and extract the result.
 *
 * Debug mode: set -Ddiag.debug=true or DIAG_DEBUG=true to inspect prompts and chat responses.
 */
@Component
public class SpringAiChatPort implements ChatPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatPort.class);

    private final ChatClient chatClient;

    public SpringAiChatPort(ChatClient chatClient) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient must not be null");
    }

    private static boolean isDebugEnabled() {
        return log.isDebugEnabled()
                || Boolean.getBoolean("diag.debug")
                || "true".equalsIgnoreCase(System.getenv("DIAG_DEBUG"));
    }

    @Override
    public ChatResult chat(String system, String user, List<Object> toolBeans) {
        Objects.requireNonNull(system,    "system must not be null");
        Objects.requireNonNull(user,      "user must not be null");
        Objects.requireNonNull(toolBeans, "toolBeans must not be null");

        if (isDebugEnabled()) {
            System.out.println("\n==================== [DIAG DEBUG: LLM PROMPT] ====================");
            System.out.println("--- USER CONTEXT ---");
            System.out.println(user);
            System.out.println("===================================================================\n");
        }

        ChatResponse response = chatClient.prompt()
                .system(system)
                .user(user)
                .tools(toolBeans.toArray())
                .call()
                .chatResponse();

        if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
            throw new IllegalStateException("ChatClient returned null response or output");
        }

        String text = response.getResult().getOutput().getText();
        if (text == null) {
            text = "";
        }

        long tokensIn  = 0L;
        long tokensOut = 0L;
        if (response.getMetadata() != null && response.getMetadata().getUsage() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage.getPromptTokens() != null) {
                tokensIn = usage.getPromptTokens().longValue();
            }
            if (usage.getCompletionTokens() != null) {
                tokensOut = usage.getCompletionTokens().longValue();
            }
        }

        if (isDebugEnabled()) {
            System.out.println("\n==================== [DIAG DEBUG: LLM RESPONSE] ====================");
            System.out.println("TOKENS IN: " + tokensIn + " | TOKENS OUT: " + tokensOut);
            System.out.println("--- RESPONSE TEXT ---");
            System.out.println(text);
            System.out.println("=====================================================================\n");
        }

        return new ChatResult(text, tokensIn, tokensOut);
    }
}
