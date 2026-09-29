package io.diag.agent.loop;

import java.util.List;

/**
 * Seam between the agent loop and the LLM provider (§5.4, D2).
 * SpringAiChatPort is the real impl; FakeChatPort is the test double.
 * Keeping the seam here means the loop never imports Spring AI directly.
 */
public interface ChatPort {
    ChatResult chat(String system, String user, List<Object> toolBeans);
}
