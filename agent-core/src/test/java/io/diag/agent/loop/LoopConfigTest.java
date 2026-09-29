package io.diag.agent.loop;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoopConfigTest {

    @Test
    void defaults_matchSpec() {
        LoopConfig c = LoopConfig.defaults();
        assertThat(c.maxIterations()).isEqualTo(5);
        assertThat(c.maxWallMs()).isEqualTo(3_600_000L);
        assertThat(c.maxTokens()).isEqualTo(500_000L);
        assertThat(c.maxCostUsd()).isEqualByComparingTo("5.00");
        assertThat(c.toolCallBound()).isEqualTo(10);
        assertThat(c.keepP95RegressionBound()).isEqualTo(0.50);
        assertThat(c.priceInPerMtok()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(c.priceOutPerMtok()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void fromEnv_emptyMap_returnsDefaults() {
        LoopConfig c = LoopConfig.fromEnv(Map.of());
        assertThat(c).isEqualTo(LoopConfig.defaults());
    }

    @Test
    void fromEnv_validOverrides_parsedCorrectly() {
        Map<String, String> env = Map.of(
                "DIAG_MAX_ITERATIONS",  "3",
                "DIAG_MAX_WALL_MS",     "1800000",
                "DIAG_MAX_TOKENS",      "250000",
                "DIAG_MAX_COST_USD",    "2.50",
                "DIAG_TOOL_BOUND",      "5",
                "DIAG_KEEP_P95_BOUND",  "0.30",
                "DIAG_PRICE_IN_MTOK",   "3.00",
                "DIAG_PRICE_OUT_MTOK",  "15.00"
        );
        LoopConfig c = LoopConfig.fromEnv(env);
        assertThat(c.maxIterations()).isEqualTo(3);
        assertThat(c.maxWallMs()).isEqualTo(1_800_000L);
        assertThat(c.maxTokens()).isEqualTo(250_000L);
        assertThat(c.maxCostUsd()).isEqualByComparingTo("2.50");
        assertThat(c.toolCallBound()).isEqualTo(5);
        assertThat(c.keepP95RegressionBound()).isEqualTo(0.30);
        assertThat(c.priceInPerMtok()).isEqualByComparingTo("3.00");
        assertThat(c.priceOutPerMtok()).isEqualByComparingTo("15.00");
    }

    @Test
    void fromEnv_blankValues_fallBackToDefaults() {
        LoopConfig c = LoopConfig.fromEnv(Map.of("DIAG_MAX_ITERATIONS", "  "));
        assertThat(c.maxIterations()).isEqualTo(5);
    }

    @Test
    void fromEnv_malformedInt_throwsIllegalArgument() {
        assertThatThrownBy(() -> LoopConfig.fromEnv(Map.of("DIAG_MAX_ITERATIONS", "not-a-number")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DIAG_MAX_ITERATIONS");
    }

    @Test
    void fromEnv_malformedDecimal_throwsIllegalArgument() {
        assertThatThrownBy(() -> LoopConfig.fromEnv(Map.of("DIAG_MAX_COST_USD", "five-dollars")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DIAG_MAX_COST_USD");
    }

    @Test
    void constructor_zeroMaxIterations_throws() {
        assertThatThrownBy(() -> new LoopConfig(0, 1, 1, BigDecimal.ONE, 1, 0.5,
                BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxIterations");
    }

    @Test
    void constructor_negativeMaxCostUsd_throws() {
        assertThatThrownBy(() -> new LoopConfig(1, 1, 1, new BigDecimal("-1"), 1, 0.5,
                BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxCostUsd");
    }

    @Test
    void constructor_nullMaxCostUsd_throws() {
        assertThatThrownBy(() -> new LoopConfig(1, 1, 1, null, 1, 0.5,
                BigDecimal.ZERO, BigDecimal.ZERO))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_negativePriceIn_throws() {
        assertThatThrownBy(() -> new LoopConfig(1, 1, 1, BigDecimal.ONE, 1, 0.5,
                new BigDecimal("-0.01"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("priceInPerMtok");
    }
}
