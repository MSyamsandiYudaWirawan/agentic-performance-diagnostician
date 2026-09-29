package io.diag.agent.config;

import com.anthropic.core.Timeout;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Configuration
public class AgentConfig {

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder){
        return builder.build();
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
            @Value("${diag.agent.llm-timeout-ms:120000}") long ms){

        if(ms <= 0){
            throw new IllegalArgumentException("diag.agent.llm-timeout-ms must be > 0, got: " + ms);
        }
        return  b -> b.timeout(
                Timeout.builder()
                        .connect(Duration.ofSeconds(10))
                        .read(Duration.ofMillis(ms))
                        .request(Duration.ofMillis(ms))
                        .build());
    }

}
