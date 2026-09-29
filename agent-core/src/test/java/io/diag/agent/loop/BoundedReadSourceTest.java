package io.diag.agent.loop;

import io.diag.agent.ToolEnvelope;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.service.EvidenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BoundedReadSourceTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Minimal fake — records trajectory events for assertion
    // -------------------------------------------------------------------------

    static class FakeEvidenceService implements EvidenceService {
        record Event(String runId, String kind, Map<String, Object> payload) {}
        final List<Event> events = new ArrayList<>();

        @Override
        public TrajectoryEvent createTrajectoryEvent(String runId, String kind,
                                                     Map<String, Object> payload,
                                                     Long tokensIn, Long tokensOut,
                                                     BigDecimal costUsd) {
            events.add(new Event(runId, kind, payload));
            return null;
        }

        // Unused stubs
        @Override public Run createRun(String t, String p, String m, String h, String a, Map<String,Object> g) { return null; }
        @Override public void transitionRunStatus(String r, RunStatus b, RunStatus a) {}
        @Override public LoadReport createLoadReport(String r, String l, LoadReportDto d, Path f) { return null; }
        @Override public JfrReport createJfrReport(String r, String l, JfrReportDto d, Path f) { return null; }
        @Override public Iteration createIteration(String r, int n, HypothesisDto h, Map<String,Object> le,
                                                   ChangeDto c, String o, String t, Long lr, Long jr,
                                                   List<FilesTouchedDto> ft, String kt, String fi) { return null; }
        @Override public void recordBaseline(String r, double p95, double p95f, double rps, double rpsf, String sha) {}
        @Override public void recordKeptSha(String r, String sha) {}
    }

    private DiagnosticTools tools() {
        // null for unused deps — DiagnosticTools.readSource only needs targetRoot
        return new DiagnosticTools(null, null, null, null, tempDir);
    }

    // -------------------------------------------------------------------------

    @Test
    void delegates_contentRoundTrips() throws Exception {
        Path file = Files.writeString(tempDir.resolve("Hello.java"), "class Hello {}");
        FakeEvidenceService svc = new FakeEvidenceService();
        BoundedReadSource brs = new BoundedReadSource(tools(), 10, svc, "run-1");

        ToolEnvelope<String> result = brs.readSource("Hello.java");

        assertThat(result.ok()).isTrue();
        assertThat(result.data()).isEqualTo("class Hello {}");
    }

    @Test
    void bound_nCallsPass_nPlusOneReturnsFailEnvelope() throws Exception {
        Files.writeString(tempDir.resolve("A.java"), "A");
        FakeEvidenceService svc = new FakeEvidenceService();
        int bound = 3;
        BoundedReadSource brs = new BoundedReadSource(tools(), bound, svc, "run-1");

        for (int i = 0; i < bound; i++) {
            assertThat(brs.readSource("A.java").ok()).isTrue();
        }

        ToolEnvelope<String> exceeded = brs.readSource("A.java");
        assertThat(exceeded.ok()).isFalse();
        assertThat(exceeded.error()).contains("bound (" + bound + ")");
    }

    @Test
    void pathEscape_returnsFailEnvelope() {
        FakeEvidenceService svc = new FakeEvidenceService();
        BoundedReadSource brs = new BoundedReadSource(tools(), 10, svc, "run-1");

        ToolEnvelope<String> result = brs.readSource("../../etc/passwd");

        assertThat(result.ok()).isFalse();
    }

    @Test
    void trajectoryEvents_callAndResultPairsInOrder() throws Exception {
        Files.writeString(tempDir.resolve("B.java"), "B");
        FakeEvidenceService svc = new FakeEvidenceService();
        BoundedReadSource brs = new BoundedReadSource(tools(), 2, svc, "run-42");

        brs.readSource("B.java");       // call 1 — ok
        brs.readSource("B.java");       // call 2 — ok
        brs.readSource("B.java");       // call 3 — bound exceeded

        // 3 calls × 2 events each = 6 events
        assertThat(svc.events).hasSize(6);

        // Each pair: TOOL_CALL then TOOL_RESULT, correct runId
        for (int i = 0; i < 6; i += 2) {
            assertThat(svc.events.get(i).kind()).isEqualTo("TOOL_CALL");
            assertThat(svc.events.get(i).runId()).isEqualTo("run-42");
            assertThat(svc.events.get(i + 1).kind()).isEqualTo("TOOL_RESULT");
            assertThat(svc.events.get(i + 1).runId()).isEqualTo("run-42");
        }

        // The bound-exceeded result must be ok=false
        Map<String, Object> exceededResult = svc.events.get(5).payload();
        assertThat(exceededResult.get("ok")).isEqualTo(false);
    }

    @Test
    void trajectoryEvents_pathEscape_stillLogsCallAndResult() {
        FakeEvidenceService svc = new FakeEvidenceService();
        BoundedReadSource brs = new BoundedReadSource(tools(), 10, svc, "run-1");

        brs.readSource("../../escape");

        assertThat(svc.events).hasSize(2);
        assertThat(svc.events.get(0).kind()).isEqualTo("TOOL_CALL");
        assertThat(svc.events.get(1).kind()).isEqualTo("TOOL_RESULT");
        assertThat(svc.events.get(1).payload().get("ok")).isEqualTo(false);
    }

    @Test
    void constructor_nullTools_throws() {
        assertThatThrownBy(() -> new BoundedReadSource(null, 10, new FakeEvidenceService(), "r"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void constructor_zeroBound_throws() {
        assertThatThrownBy(() -> new BoundedReadSource(tools(), 0, new FakeEvidenceService(), "r"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound");
    }
}
