package io.diag.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.RunStatus;
import io.diag.evidence.RunnerGateApp;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.service.EvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = RunnerGateApp.class, properties = {
        "spring.datasource.url=jdbc:postgresql://localhost:5432/diagnostician?stringtype=unspecified",
        "spring.datasource.username=diag",
        "spring.datasource.password=diag",
        "evidence.artifacts-root=C:/study/agentic-performance-diagnostician/evidence/artifacts"
})
class Step5VerifyGate {

    static final Path PROJECT_ROOT  = Path.of("C:/study/agentic-performance-diagnostician");
    static final Path TARGET_REPO   = PROJECT_ROOT.resolve("targets/spring-petclinic");
    static final Path EVIDENCE_ROOT = PROJECT_ROOT.resolve("evidence/artifacts");
    static final Path BUILD_LOG     = PROJECT_ROOT.resolve("step5-gate-build.log");

    @Autowired EvidenceService evidenceService;
    @Autowired ObjectMapper    mapper;

    @Test
    void jfrLandsOnDiskAndInPostgres() throws Exception {
        if (!Boolean.getBoolean("step5.gate")) return;

        String runId = evidenceService
                .createRun("S1", "manual", "none", "step5-gate", "manual", Map.of())
                .getId();

        try {
            Path jarRel = new TargetBuilder().build(TARGET_REPO, BUILD_LOG);
            Files.deleteIfExists(BUILD_LOG);

            JfrReport report;
            try (TargetStack stack = new TargetStack(PROJECT_ROOT, TARGET_REPO, runId, EVIDENCE_ROOT)) {
                JfrCapture capture = new JfrCapture(stack, evidenceService, runId);
                stack.up("baseline-1", jarRel);
                stack.runK6("baseline-1", false);
                report = capture.capture("baseline-1");
            }

            assertNotNull(report.getId(),        "JfrReport must be persisted to Postgres");
            assertNotNull(report.getJfrSha256(), "sha256 must be recorded");
            assertFalse(report.getJfrSha256().isBlank(), "sha256 must not be blank");

            Path jfr = Path.of(report.getJfrPath());
            assertTrue(Files.exists(jfr) && Files.size(jfr) > 0, "jfr file missing or empty: " + jfr);

            String summary = jfrSummary(jfr);
            assertTrue(summary.contains("jdk.ExecutionSample"),  "missing jdk.ExecutionSample:\n"  + summary);
            assertTrue(summary.contains("jdk.JavaMonitorEnter"), "missing jdk.JavaMonitorEnter:\n" + summary);

            evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.COMPLETED);
        } finally {
            try {
                evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.INCOMPLETE);
            } catch (IllegalStateException alreadyTransitioned) {
                System.err.println("[step5-gate] " + alreadyTransitioned.getMessage());
            }
        }
    }

    private static String jfrSummary(Path jfr) throws Exception {
        Path jfrBin = Path.of(System.getProperty("java.home")).resolve("bin/jfr");
        Process p = new ProcessBuilder(jfrBin.toString(), "summary", jfr.toString())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes());
        assertTrue(p.waitFor(30, TimeUnit.SECONDS), "jfr summary timed out");
        assertEquals(0, p.exitValue(), "jfr summary failed:\n" + out);
        return out;
    }
}
