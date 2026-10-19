package io.diag.agent.config;

import com.anthropic.core.Timeout;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.advisor.InfraRetryAdvisor;
import io.diag.agent.advisor.SchemaSelfHealingAdvisor;
import io.diag.agent.advisor.SecuritySanitizerAdvisor;
import io.diag.agent.advisor.TelemetryAndAuditAdvisor;
import io.diag.agent.advisor.TokenBudgetAdvisor;
import io.diag.agent.decision.DecisionValidator;
import io.diag.agent.loop.LoopConfig;
import io.diag.evidence.service.EvidenceService;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

@Configuration
public class AgentConfig {

    public static final List<String> DEFAULT_BLOCKED_PATTERNS = List.of(
            "rm -rf", "DROP TABLE", "ignore previous instructions"
    );

    @Bean
    public ChatClient chatClient(
            ChatClient.Builder builder,
            @Autowired(required = false) List<CallAdvisor> advisors) {
        if (advisors != null && !advisors.isEmpty()) {
            builder.defaultAdvisors(advisors.toArray(CallAdvisor[]::new));
        }
        return builder.build();
    }

    /**
     * Assembles the 5-layer CallAdvisor onion in deterministic order (10 -> 20 -> 30 -> 40 -> 50).
     */
    public List<CallAdvisor> buildAdvisorOnion(
            DecisionValidator validator,
            ObjectMapper mapper,
            LoopConfig loopConfig,
            EvidenceService evidenceService) {
        return List.of(
                new SecuritySanitizerAdvisor(DEFAULT_BLOCKED_PATTERNS),
                new SchemaSelfHealingAdvisor(validator, mapper),
                new TokenBudgetAdvisor(loopConfig),
                new TelemetryAndAuditAdvisor(evidenceService),
                new InfraRetryAdvisor()
        );
    }

    @Bean
    public GenParams genParams(
            @Value("${diag.agent.provider:openai}") String provider,
            @Value("${diag.agent.model:gpt-4o}") String model,
            @Value("${diag.agent.temperature:0.2}") double temperature,
            @Value("${diag.agent.max-tokens:4096}") int maxTokens) {
        return new GenParams(provider, model, temperature, maxTokens);
    }

    @Bean
    public AnthropicHttpClientBuilderCustomizer llmTimeout(
            @Value("${diag.agent.llm-timeout-ms:120000}") long ms) {
        if (ms <= 0) {
            throw new IllegalArgumentException("diag.agent.llm-timeout-ms must be > 0, got: " + ms);
        }
        return b -> b.timeout(
                Timeout.builder()
                        .connect(Duration.ofSeconds(10))
                        .read(Duration.ofMillis(ms))
                        .request(Duration.ofMillis(ms))
                        .build());
    }
}
