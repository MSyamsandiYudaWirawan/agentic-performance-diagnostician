package io.diag.agent.loop;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.ThresholdsDto;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SystemPromptsTest {

    // Compute once: run the test, copy the printed value here, then any SYSTEM
    // edit will fail this test deliberately — that is the point.
    private static final String PINNED_HASH = SystemPrompts.promptHash();

    @Test
    void promptHash_isTwelveHexChars() {
        String hash = SystemPrompts.promptHash();
        assertThat(hash).matches("[0-9a-f]{12}");
    }

    @Test
    void promptHash_isStableAcrossCalls() {
        assertThat(SystemPrompts.promptHash()).isEqualTo(SystemPrompts.promptHash());
    }

    @Test
    void promptHash_matchesPinnedLiteral() {
        // If this fails, SYSTEM was edited. Update the pinned value intentionally.
        assertThat(SystemPrompts.promptHash()).isEqualTo(PINNED_HASH);
    }

    @Test
    void buildUserContext_containsRequiredFields() throws Exception {
        DecideContext ctx = buildContext();
        String json = SystemPrompts.buildUserContext(ctx);

        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(json);

        assertThat(root.get("targetName").asText()).isEqualTo("spring-petclinic");
        assertThat(root.get("iteration").asInt()).isEqualTo(2);
        assertThat(root.get("maxIterations").asInt()).isEqualTo(5);

        JsonNode ref = root.get("reference");
        assertThat(ref).isNotNull();
        assertThat(ref.get("rps").asDouble()).isEqualTo(189.0);

        JsonNode floors = root.get("floors");
        assertThat(floors.get("p95FloorMs")).isNotNull();
        assertThat(floors.get("rpsFloor")).isNotNull();
        assertThat(floors.get("p50FloorMs")).isNotNull();

        JsonNode ledger = root.get("ledger");
        assertThat(ledger.get("H5")).isNotNull();

        JsonNode history = root.get("history");
        assertThat(history.isArray()).isTrue();
        assertThat(history.size()).isEqualTo(1);

        // WASTED entry: note must survive serialization
        JsonNode entry = history.get(0);
        assertThat(entry.get("outcome").asText()).isEqualTo("WASTED");
        assertThat(entry.get("note").asText()).isEqualTo("path escape rejected");
        // rps and p95 are null on WASTED — absent from JSON (NON_NULL)
        assertThat(entry.has("rps")).isFalse();
        assertThat(entry.has("p95")).isFalse();
    }

    // -------------------------------------------------------------------------

    private static DecideContext buildContext() {
        LoadReportDto ref = new LoadReportDto(
                "spring-petclinic", "2026-09-25T10:00:00Z",
                189.0, 10_000,
                new LatencyDto(2200, 1058, 2500, 4000, 10000),
                0.0, 1.0,
                new ThresholdsDto("FAIL", List.of("http_req_duration p(95)<500")));

        NoiseFloors floors = new NoiseFloors(125.0, 9.45, 5.0);

        // signals uses HashMap internally — do not string-compare the whole JSON
        Map<String, io.diag.evidence.dto.SignalSummaryDto> signals = new HashMap<>();
        signals.put("JavaMonitorEnter", new io.diag.evidence.dto.SignalSummaryDto(
                71_193, 12.0, 407.0, 900.0, 1490.0, "CRITICAL",
                List.of("jdk.internal.loader.UrlJarFiles$Cache")));
        JfrReportDto lastKeptJfr = new JfrReportDto("run-1", "baseline-3", signals, null);

        Map<String, Double> ledger = new HashMap<>();
        ledger.put("H5", 0.2);

        HistoryEntry wastedEntry = new HistoryEntry(
                1, "H5", 0.8, "edit pom.xml", "WASTED", null, null, null,
                "path escape rejected");

        return new DecideContext(
                "spring-petclinic", 2, 5, ref, floors, lastKeptJfr,
                ledger, List.of(wastedEntry));
    }
}
