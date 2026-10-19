package io.diag.agent.advisor;

import io.diag.agent.advisor.DiagnosticAdvisorContracts.Orders;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.SecurityPolicyViolationException;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * LEVEL 10: Pre-Call Prompt Security & Policy Guardrail Advisor.
 *
 * Practice Reference: phase04-ex01 KeywordGuardrailAdvisor / RegexPatternGuardrailAdvisor.
 */
public class SecuritySanitizerAdvisor implements CallAdvisor {

    private final List<String> blockedPatterns;

    public SecuritySanitizerAdvisor(List<String> blockedPatterns) {
        this.blockedPatterns = Objects.requireNonNull(blockedPatterns, "blockedPatterns must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "ChatClientRequest must not be null" );
        Objects.requireNonNull(chain, "CallAdvisorChain must not be null");

        String contents = request.prompt().getContents().trim().toLowerCase(Locale.ROOT);
        for (String blocked : blockedPatterns) {
            if (contents.contains(blocked.trim().toLowerCase(Locale.ROOT))) {
                throw new SecurityPolicyViolationException("Blocked security pattern detected: " + blocked);
            }
        }
        return chain.nextCall(request);
    }

    @Override
    public int getOrder() {
        return Orders.SECURITY_SANITIZER;
    }

    @Override
    public String getName() {
        return "SecuritySanitizerAdvisor";
    }
}
