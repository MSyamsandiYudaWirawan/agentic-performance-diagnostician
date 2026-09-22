package io.diag.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

}
