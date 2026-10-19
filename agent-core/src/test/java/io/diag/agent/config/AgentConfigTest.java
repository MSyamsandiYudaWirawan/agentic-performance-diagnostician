package io.diag.agent.config;

import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.http.okhttp.AnthropicHttpClientBuilderCustomizer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentConfigTest {

    private final AgentConfig config = new AgentConfig();

    @Test
    void llmTimeout_positiveMs_returnsCustomizer() {
        AnthropicHttpClientBuilderCustomizer customizer = config.llmTimeout(120_000L);
        assertThat(customizer).isNotNull();
    }

    @Test
    void llmTimeout_zeroMs_throwsIllegalArgument() {
        assertThatThrownBy(() -> config.llmTimeout(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be > 0");
    }

    @Test
    void llmTimeout_negativeMs_throwsIllegalArgument() {
        assertThatThrownBy(() -> config.llmTimeout(-500))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be > 0");
    }

    @Test
    void genParams_constructsWithValues() {
        GenParams params = config.genParams("anthropic", "claude-3-5-sonnet", 0.1, 8192);
        assertThat(params.provider()).isEqualTo("anthropic");
        assertThat(params.model()).isEqualTo("claude-3-5-sonnet");
        assertThat(params.temperature()).isEqualTo(0.1);
        assertThat(params.maxTokens()).isEqualTo(8192);
    }

    @Test
    void buildAdvisorOnion_returnsFiveOrderedAdvisors() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var validator = new io.diag.agent.decision.DecisionValidator(
                mapper, jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        var evidenceService = org.mockito.Mockito.mock(io.diag.evidence.service.EvidenceService.class);

        var advisors = config.buildAdvisorOnion(
                validator, mapper, io.diag.agent.loop.LoopConfig.defaults(), evidenceService);

        assertThat(advisors).hasSize(5);
        assertThat(advisors).extracting(org.springframework.ai.chat.client.advisor.api.CallAdvisor::getOrder)
                .containsExactly(10, 20, 30, 40, 50);
    }
}
