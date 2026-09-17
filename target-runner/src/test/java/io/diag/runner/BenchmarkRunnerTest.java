package io.diag.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.ThresholdsDto;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.service.EvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BenchmarkRunnerTest {

    @TempDir Path tmp;

    TargetStack     stack;
    EvidenceService evidenceService;
    EnvelopeGuard   envelopeGuard;
    ObjectMapper    mapper = new ObjectMapper();
    String          runId  = "test-123456";

    BenchmarkRunner runner;

    @BeforeEach
    void setUp() {
        stack           = mock(TargetStack.class);
        evidenceService = mock(EvidenceService.class);
        envelopeGuard   = mock(EnvelopeGuard.class);
        runner = new BenchmarkRunner(stack, evidenceService, envelopeGuard, mapper, tmp, runId, "petclinic");
    }

    // -------------------------------------------------------------------------
    // constructor null guards (§4 fail-fast)
    // -------------------------------------------------------------------------

    @Test
    void constructor_rejectsNullStack() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(null, evidenceService, envelopeGuard, mapper, tmp, runId, "petclinic"));
    }

    @Test
    void constructor_rejectsNullEvidenceService() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(stack, null, envelopeGuard, mapper, tmp, runId, "petclinic"));
    }

    @Test
    void constructor_rejectsNullMapper() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(stack, evidenceService, envelopeGuard, null, tmp, runId, "petclinic"));
    }

    @Test
    void constructor_rejectsNullEnvelopeGuard() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(stack, evidenceService, null, mapper, tmp, runId, "petclinic"));
    }

    @Test
    void constructor_rejectsNullEvidenceRoot() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(stack, evidenceService, envelopeGuard, mapper, null, runId, "petclinic"));
    }

    @Test
    void constructor_rejectsNullRunId() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(stack, evidenceService, envelopeGuard, mapper, tmp, null, "petclinic"));
    }

    @Test
    void constructor_rejectsNullTargetName() {
        assertThrows(NullPointerException.class, () ->
                new BenchmarkRunner(stack, evidenceService, envelopeGuard, mapper, tmp, runId, null));
    }

    @Test
    void run_rejectsNullLabel() {
        assertThrows(NullPointerException.class, () -> runner.run(null, false));
    }

    // -------------------------------------------------------------------------
    // smoke NOT_TESTABLE — http_reqs.count = 0
    // -------------------------------------------------------------------------

    @Test
    void smoke_notTestable_whenHttpReqsCountZero() throws Exception {
        writeSummary("smoke-0", summaryJson(0, 0.0, 0, 0, 0, 0, 0, 0.0, 0.0, true));
        when(stack.runK6("smoke-0", true)).thenReturn(0);
        when(evidenceService.createLoadReport(eq(runId), eq("smoke-0"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        LoadReport result = runner.run("smoke-0", true);

        assertEquals("NOT_TESTABLE", result.getPayload().thresholds().verdict());
        // must persist — never throw
        verify(evidenceService).createLoadReport(eq(runId), eq("smoke-0"),
                argThat(dto -> "NOT_TESTABLE".equals(dto.thresholds().verdict())), any());
    }

    // -------------------------------------------------------------------------
    // smoke NOT_TESTABLE — checks.rate = 0 (count > 0 is not enough)
    // -------------------------------------------------------------------------

    @Test
    void smoke_notTestable_whenCheckRateZero() throws Exception {
        writeSummary("smoke-1", summaryJson(100, 20.0, 50, 40, 80, 100, 200, 0.005, 0.0, true));
        when(stack.runK6("smoke-1", true)).thenReturn(0);
        when(evidenceService.createLoadReport(eq(runId), eq("smoke-1"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        LoadReport result = runner.run("smoke-1", true);

        assertEquals("NOT_TESTABLE", result.getPayload().thresholds().verdict());
    }

    // -------------------------------------------------------------------------
    // smoke passes → normal parse + persist
    // -------------------------------------------------------------------------

    @Test
    void smoke_pass_parsesAndPersists() throws Exception {
        writeSummary("smoke-2", summaryJson(50, 10.0, 100, 80, 120, 200, 300, 0.002, 0.99, true));
        when(stack.runK6("smoke-2", true)).thenReturn(0);
        when(evidenceService.createLoadReport(eq(runId), eq("smoke-2"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        LoadReport result = runner.run("smoke-2", true);

        assertEquals("PASS", result.getPayload().thresholds().verdict());
        verify(evidenceService).createLoadReport(eq(runId), eq("smoke-2"),
                argThat(dto -> dto.rps() > 0), any());
        // envelope assertion fires on every run, smoke included (scope §10.28)
        verify(envelopeGuard).assertBudget("test-123456-service-1");
    }

    // -------------------------------------------------------------------------
    // envelope tamper — the run is invalid, nothing persisted
    // -------------------------------------------------------------------------

    @Test
    void envelopeTamper_throwsAndPersistsNothing() throws Exception {
        writeSummary("baseline-1", summaryJson(1000, 189.0, 1058, 900, 2500, 3000, 4000, 0.001, 0.99, false));
        when(stack.runK6("baseline-1", false)).thenReturn(0);
        doThrow(new IllegalStateException("envelope tampered"))
                .when(envelopeGuard).assertBudget(any());

        assertThrows(IllegalStateException.class, () -> runner.run("baseline-1", false));
        // a tampered envelope invalidates the measurement — never persisted as a result
        verifyNoInteractions(evidenceService);
    }

    // -------------------------------------------------------------------------
    // full run — exit 99 is a measured FAIL, not an infrastructure error
    // -------------------------------------------------------------------------

    @Test
    void fullRun_exit99_parsedAsFail() throws Exception {
        writeSummary("baseline-1", summaryJson(1000, 189.0, 1058, 900, 2500, 3000, 4000, 0.001, 0.99, false));
        when(stack.runK6("baseline-1", false)).thenReturn(99);
        when(evidenceService.createLoadReport(eq(runId), eq("baseline-1"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        // must not throw — exit 99 is a measured result
        LoadReport result = runner.run("baseline-1", false);

        assertEquals("FAIL", result.getPayload().thresholds().verdict());
        // breached entries read "<metric> <expr>", e.g. "http_req_duration p(95)<500"
        assertTrue(result.getPayload().thresholds().breached().stream().anyMatch(b -> b.startsWith("http_req_duration")),
                "breached list must name the failing threshold: " + result.getPayload().thresholds().breached());
    }

    // -------------------------------------------------------------------------
    // full run — any other non-zero exit is an infrastructure error
    // -------------------------------------------------------------------------

    @Test
    void fullRun_nonZeroNon99Exit_throws() throws Exception {
        when(stack.runK6("baseline-1", false)).thenReturn(1);

        assertThrows(IllegalStateException.class, () -> runner.run("baseline-1", false));
        // nothing persisted — the run is invalid before we even read the summary
        verifyNoInteractions(evidenceService);
    }

    // -------------------------------------------------------------------------
    // parse — targetName flows through to the dto
    // -------------------------------------------------------------------------

    @Test
    void parse_targetNameInDto() throws Exception {
        writeSummary("baseline-1", summaryJson(1000, 189.0, 1058, 900, 2500, 3000, 4000, 0.001, 0.99, false));
        when(stack.runK6("baseline-1", false)).thenReturn(99);
        when(evidenceService.createLoadReport(eq(runId), eq("baseline-1"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        LoadReport result = runner.run("baseline-1", false);

        assertEquals("petclinic", result.getPayload().repo());
    }

    // -------------------------------------------------------------------------
    // parse — all latency fields mapped correctly
    // -------------------------------------------------------------------------

    @Test
    void parse_latencyFieldsMapped() throws Exception {
        writeSummary("baseline-1", summaryJson(1000, 189.0, 1058.0, 900.0, 2500.0, 3000.0, 4000.0, 0.001, 0.99, true));
        when(stack.runK6("baseline-1", false)).thenReturn(0);
        when(evidenceService.createLoadReport(eq(runId), eq("baseline-1"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        LoadReport result = runner.run("baseline-1", false);

        LatencyDto lat = result.getPayload().latency();
        assertEquals(1058.0, lat.avg(),  0.001);
        assertEquals(900.0,  lat.p50(),  0.001);
        assertEquals(2500.0, lat.p95(),  0.001);
        assertEquals(3000.0, lat.p99(),  0.001);
        assertEquals(4000.0, lat.max(),  0.001);
    }

    // -------------------------------------------------------------------------
    // parse — all thresholds passing → PASS verdict, empty breached list
    // -------------------------------------------------------------------------

    @Test
    void parse_allThresholdsPassing_verdictPass() throws Exception {
        writeSummary("iter-1", summaryJson(1000, 300.0, 200, 150, 400, 450, 600, 0.001, 0.99, true));
        when(stack.runK6("iter-1", false)).thenReturn(0);
        when(evidenceService.createLoadReport(eq(runId), eq("iter-1"), any(), any()))
                .thenAnswer(inv -> stubReport(inv.getArgument(2)));

        LoadReport result = runner.run("iter-1", false);

        assertEquals("PASS", result.getPayload().thresholds().verdict());
        assertTrue(result.getPayload().thresholds().breached().isEmpty());
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private void writeSummary(String label, String json) throws Exception {
        Path dir = tmp.resolve(runId).resolve(label);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("k6-summary.json"), json);
    }

    /**
     * Minimal k6 summary JSON matching the REAL handleSummary shape (verified
     * against an actual gate-run artifact 2026-09-17): thresholds nest under
     * each metric, keyed by expression — there is no top-level thresholds key.
     * thresholdPassed=false puts ok=false on http_req_duration's p(95) to
     * simulate a FAIL run.
     */
    private String summaryJson(long reqCount, double reqRate,
                               double avg, double med, double p95, double p99, double max,
                               double failRate, double checkRate, boolean thresholdPassed) {
        return """
                {
                  "metrics": {
                    "http_reqs":         { "values": { "count": %d, "rate": %f } },
                    "http_req_duration": { "values": { "avg": %f, "med": %f, "p(95)": %f, "p(99)": %f, "max": %f },
                                           "thresholds": { "p(95)<500": { "ok": %b } } },
                    "http_req_failed":   { "values": { "rate": %f },
                                           "thresholds": { "rate<0.01": { "ok": true } } },
                    "checks":            { "values": { "rate": %f },
                                           "thresholds": { "rate>0.95": { "ok": true } } }
                  }
                }
                """.formatted(reqCount, reqRate, avg, med, p95, p99, max, thresholdPassed, failRate, checkRate);
    }

    /** Returns a LoadReport whose payload is the dto passed in — lets assertions inspect what was persisted. */
    private LoadReport stubReport(LoadReportDto dto) {
        LoadReport r = new LoadReport();
        r.setId(1L);
        r.setPayload(dto);
        return r;
    }
}
