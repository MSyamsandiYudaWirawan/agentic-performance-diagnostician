package io.diag.runner;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.RunStatus;
import io.diag.evidence.RunnerGateApp;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.service.EvidenceService;
import io.diag.runner.service.BenchmarkRunner;
import io.diag.runner.service.DockerEnvelopeGuard;
import io.diag.runner.service.EnvelopeGuard;
import io.diag.runner.service.TargetBuilder;
import io.diag.runner.service.TargetStack;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 4 verify gate (build-steps step 4): the full benchmark path with zero
 * LLM — build -> smoke gate -> committed 200-VU profile -> parse -> persist to
 * the REAL always-on evidence Postgres. This is the calibration gate: it must
 * reproduce the stock baseline ballpark (REF h3: rps ~189, p95 ~2500ms, verdict
 * FAIL with p(95)&lt;500 breached). Wildly off -> fix the envelope before
 * anything downstream depends on numbers.
 *
 * Opt-in (real Docker + 60s benchmark + diag-evidence DB rows):
 *   mvn -pl target-runner -am test -Dstep4.gate=true -Dtest=Step4VerifyGate \
 *       -Dsurefire.failIfNoSpecifiedTests=false
 * Takes ~4-6 min. Host discipline while it runs (open-questions D17): no heavy
 * apps — this run calibrates the noise floor.
 *
 * Single-flight (§10.27): only ONE gate at a time — a second createRun while
 * this one is RUNNING is refused by design.
 */
@SpringBootTest(classes = RunnerGateApp.class, properties = {
        // the always-on evidence Postgres (docker/evidence compose) — Flyway
        // migrates on first connect; rows land where the agent loop will write
        "spring.datasource.url=jdbc:postgresql://localhost:5432/diagnostician?stringtype=unspecified",
        "spring.datasource.username=diag",
        "spring.datasource.password=diag",
        // same dir k6 writes into via the envelope mount — ArtifactStore's
        // same-file Files.copy(p, p, REPLACE_EXISTING) is a verified no-op on
        // this JDK/Windows, so store() just hashes in place
        "evidence.artifacts-root=C:/study/agentic-performance-diagnostician/evidence/artifacts"
})
class Step4VerifyGate {

    static final Path PROJECT_ROOT  = Path.of("C:/study/agentic-performance-diagnostician");
    static final Path TARGET_REPO   = PROJECT_ROOT.resolve("targets/spring-petclinic");
    static final Path EVIDENCE_ROOT = PROJECT_ROOT.resolve("evidence/artifacts");
    static final Path BUILD_LOG     = PROJECT_ROOT.resolve("step4-gate-build.log");

    @Autowired
    EvidenceService evidenceService;

    @Autowired
    ObjectMapper mapper;

    @Test
    void fullGate_reproducesStockBaseline() throws Exception {
        // opt-IN: ~4-6 min of real Docker + a 200-VU benchmark
        if (!Boolean.getBoolean("step4.gate")) return;

        String runId = evidenceService
                .createRun("S1", "manual", "none", "step4-gate", "manual", Map.of())
                .getId();

        try {
            Path jarRel = new TargetBuilder().build(TARGET_REPO, BUILD_LOG);
            Files.deleteIfExists(BUILD_LOG);

            try (TargetStack stack = new TargetStack(PROJECT_ROOT, TARGET_REPO, runId, EVIDENCE_ROOT)) {
                BenchmarkRunner runner = new BenchmarkRunner(
                        stack, evidenceService, new DockerEnvelopeGuard(),
                        mapper, EVIDENCE_ROOT, runId, "spring-petclinic");

                // --- smoke gate: own subdir (§10.15); a healthy stock app must be testable ---
                stack.up("smoke-1", jarRel);
                LoadReport smoke = runner.run("smoke-1", true);
                stack.stopAndHarvest("smoke-1");
                assertNotEquals("NOT_TESTABLE", smoke.getPayload().thresholds().verdict(),
                        "smoke gate failed on a healthy stock app — envelope problem, fix before proceeding");

                // --- full benchmark: the calibration run (fresh container = H2 reset, comparability) ---
                stack.up("baseline-1", jarRel);
                LoadReport baseline = runner.run("baseline-1", false);
                stack.stopAndHarvest("baseline-1");

                LoadReportDto p = baseline.getPayload();

                assertNotNull(baseline.getId(), "LoadReport row must be persisted to Postgres");
                assertEquals("FAIL", p.thresholds().verdict(), "stock app fails p(95)<500 by design");
                // breached entries read "<metric> <expr>", e.g. "http_req_duration p(95)<500"
                assertTrue(p.thresholds().breached().stream().anyMatch(b -> b.startsWith("http_req_duration")),
                        "breached list must name the p(95) threshold: " + p.thresholds().breached());
                assertTrue(p.rps() > 100 && p.rps() < 300,
                        "rps ballpark ~189 (REF h3), got " + p.rps());
                assertTrue(p.latency().p95() >= 1500,
                        "p95 ballpark ~2500ms (REF h3), got " + p.latency().p95());
                assertTrue(p.failRate() < 0.02,
                        "stock app serves without errors, got failRate " + p.failRate());
            }

            evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.COMPLETED);
        } finally {
            // best-effort: a failed gate must not leave a RUNNING row holding the
            // single-flight lock (§10.27). INCOMPLETE is the honest state for a
            // killed run; the IllegalStateException means it already transitioned
            // (COMPLETED on the success path above) — both fine, print and move on.
            try {
                evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.INCOMPLETE);
            } catch (IllegalStateException alreadyTransitioned) {
                System.err.println("[step4-gate] run " + runId + " already transitioned: "
                        + alreadyTransitioned.getMessage());
            }
        }
    }
}
