package io.diag.agent.loop;

import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.ThresholdsDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContextAndResultValidationTest {

    @Test
    void chatResult_nullText_throwsNpe() {
        assertThatThrownBy(() -> new ChatResult(null, 10, 10))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("text must not be null");
    }

    @Test
    void chatResult_negativeTokensIn_throwsIllegalArgument() {
        assertThatThrownBy(() -> new ChatResult("ok", -1, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokensIn");
    }

    @Test
    void chatResult_negativeTokensOut_throwsIllegalArgument() {
        assertThatThrownBy(() -> new ChatResult("ok", 10, -5))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tokensOut");
    }

    @Test
    void historyEntry_zeroOrNegativeN_throwsIllegalArgument() {
        assertThatThrownBy(() -> new HistoryEntry(0, "H1", 0.5, "desc", "KEPT", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n must be > 0");
    }

    @Test
    void historyEntry_nullOutcome_throwsNpe() {
        assertThatThrownBy(() -> new HistoryEntry(1, "H1", 0.5, "desc", null, null, null, null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("outcome must not be null");
    }

    @Test
    void decideContext_nullTargetName_throwsNpe() {
        LoadReportDto ref = sampleLoadReport();
        NoiseFloors floors = new NoiseFloors(10.0, 5.0, 2.0);
        assertThatThrownBy(() -> new DecideContext(null, 1, 5, ref, floors, null, Map.of(), List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("targetName");
    }

    @Test
    void decideContext_defensivelyCopiesLedgerAndHistory() {
        LoadReportDto ref = sampleLoadReport();
        NoiseFloors floors = new NoiseFloors(10.0, 5.0, 2.0);
        java.util.Map<String, Double> mutableLedger = new java.util.HashMap<>();
        mutableLedger.put("H1", 0.2);

        java.util.List<HistoryEntry> mutableHistory = new java.util.ArrayList<>();
        mutableHistory.add(new HistoryEntry(1, "H1", 0.5, "desc", "KEPT", "RPS", 100.0, 50.0, null));

        DecideContext ctx = new DecideContext("petclinic", 2, 5, ref, floors, null, mutableLedger, mutableHistory);

        // Mutating original collections does not affect DecideContext's internal state
        mutableLedger.put("H2", 0.8);
        mutableHistory.clear();

        assertThat(ctx.ledger()).containsOnlyKeys("H1");
        assertThat(ctx.history()).hasSize(1);
    }

    private static LoadReportDto sampleLoadReport() {
        return new LoadReportDto(
                "petclinic", "2026-09-25T10:00:00Z",
                100.0, 1000,
                new LatencyDto(100, 100, 100, 100, 100),
                0.0, 1.0,
                new ThresholdsDto("PASS", List.of()));
    }
}
