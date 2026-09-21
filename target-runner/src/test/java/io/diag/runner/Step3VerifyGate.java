package io.diag.runner;

import io.diag.runner.config.DockerPaths;
import io.diag.runner.service.TargetBuilder;
import io.diag.runner.service.TargetStack;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 3 verify gate — build → up → healthy → 200 → smoke → summary exists → JFR non-empty
 * → down → evidence stack untouched.
 *
 * Runs against the real Docker daemon; takes ~5–8 minutes total, so it is
 * SKIPPED by default — run explicitly with -Dstep3.gate=true. Step 4 will
 * multiply docker-dependent tests; they must not fire on every mvn test.
 */
class Step3VerifyGate {

    static final Path PROJECT_ROOT  = Path.of("C:/study/agentic-performance-diagnostician");
    static final Path TARGET_REPO   = PROJECT_ROOT.resolve("targets/spring-petclinic");
    static final Path EVIDENCE_ROOT = PROJECT_ROOT.resolve("evidence/artifacts");
    static final Path BUILD_LOG     = PROJECT_ROOT.resolve("step3-gate-build.log");

    @Test
    void fullGate() throws Exception {
        // opt-IN: 5–8 min of Docker per run; default skip keeps mvn test fast
        if (!Boolean.getBoolean("step3.gate")) return;

        String runId = "gate-" + DateTimeFormatter.ofPattern("HHmmss").format(LocalTime.now());

        // 1 — build
        Path jarRel = new TargetBuilder().build(TARGET_REPO, BUILD_LOG);
        assertTrue(jarRel.toString().endsWith(".jar"), "expected a .jar, got: " + jarRel);
        Files.deleteIfExists(BUILD_LOG);

        // 2 — up + healthy (--wait blocks until healthcheck passes)
        try (TargetStack stack = new TargetStack(PROJECT_ROOT, TARGET_REPO, runId, EVIDENCE_ROOT)) {
            stack.up("smoke-0", jarRel);

            // 3 — HTTP 200 from inside the run's Docker network (no host port exposed — runs must be isolated)
            Process curl = new ProcessBuilder(
                "docker", "run", "--rm",
                "--network", runId + "_default",
                "curlimages/curl",
                "-s", "-o", "/dev/null", "-w", "%{http_code}",
                "http://service:8080/owners/find"
            ).redirectErrorStream(true).start();
            String httpCode = new String(curl.getInputStream().readAllBytes()).trim();
            assertTrue(curl.waitFor(60, TimeUnit.SECONDS), "curl probe timed out");
            assertEquals("200", httpCode, "/owners/find must return 200");

            // 4 — smoke k6 run (2 VUs / 5s); exit 0 expected on a healthy stack
            int k6Exit = stack.runK6("smoke-0", true);
            assertEquals(0, k6Exit, "smoke k6 exit must be 0 (thresholds passed); got: " + k6Exit);

            // 5 — k6 summary JSON written to evidence dir
            Path summary = EVIDENCE_ROOT.resolve(runId).resolve("smoke-0").resolve("k6-summary.json");
            assertTrue(Files.exists(summary) && Files.size(summary) > 0, "k6 summary missing: " + summary);

            // 6 — stop + JFR harvest
            Path jfr = stack.stopAndHarvest("smoke-0");
            assertTrue(Files.size(jfr) > 0, "JFR must be non-empty: " + jfr);

            // 7 — down() called by try-with-resources close()
        }

        // 7b — teardown actually clean: close() swallows down() failures by design,
        // so assert it here — orphaned containers eat the VM budget (scope §10.27)
        ProcessBuilder psBuilder = new ProcessBuilder("docker", "compose",
                "-f", PROJECT_ROOT.resolve("docker/envelope.yml").toString(),
                "-f", TARGET_REPO.resolve("compose-service.yml").toString(),
                "-p", runId,
                "ps", "-a", "-q");
        psBuilder.environment().put("REPO_DIR", DockerPaths.toMount(TARGET_REPO));
        // JAR_FILE must be set too: compose parses BOTH files on every command,
        // and an unset build-arg var emits a stderr warning that pollutes the
        // asserted output below (redirectErrorStream merges it in).
        psBuilder.environment().put("JAR_FILE", jarRel.toString().replace('\\', '/'));
        psBuilder.environment().put("JFR_SUBDIR", "smoke-0");
        psBuilder.environment().put("EVIDENCE_DIR", DockerPaths.toMount(EVIDENCE_ROOT.resolve(runId)));
        psBuilder.environment().put("BENCHMARKS_DIR", DockerPaths.toMount(PROJECT_ROOT.resolve("benchmarks")));
        psBuilder.redirectErrorStream(true);
        Process ps2 = psBuilder.start();
        String ps2Out = new String(ps2.getInputStream().readAllBytes());
        assertTrue(ps2.waitFor(30, TimeUnit.SECONDS), "compose ps timed out");
        assertEquals(0, ps2.exitValue(), "compose ps -a -q failed: " + ps2Out);
        assertTrue(ps2Out.isBlank(), "orphaned containers/networks after down(): " + ps2Out);

        // 8 — evidence postgres still running (never torn down by a per-run stack)
        Process ps = new ProcessBuilder("docker", "ps", "--filter", "name=diag-evidence", "--format", "{{.Names}}")
                .redirectErrorStream(true)
                .start();
        String psOut = new String(ps.getInputStream().readAllBytes());
        ps.waitFor();
        assertTrue(psOut.contains("diag-evidence"), "evidence postgres must still be running; got: " + psOut);
    }
}
