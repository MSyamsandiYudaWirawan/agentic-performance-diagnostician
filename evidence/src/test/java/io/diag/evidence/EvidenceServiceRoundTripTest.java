package io.diag.evidence;

import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.dto.ThresholdsDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.service.EvidenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Build-step-1 verify gate: real Postgres (test envelope only), Flyway
 * migrates, the full evidence chain round-trips through the jsonb columns,
 * and the JFR artifact lands on disk with its sha256 recorded.
 */
// artifacts-root pinned under target/ — the default is relative and would
// litter the module tree (evidence/evidence/artifacts) on every test run
@SpringBootTest(properties = "evidence.artifacts-root=target/test-artifacts")
@Testcontainers
class EvidenceServiceRoundTripTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("evidence")
            .withUsername("evidence")
            .withPassword("evidence")
            // jsonb columns receive plain String params — Postgres rejects them
            // as character varying without this (getJdbcUrl() already carries
            // query params, so it must be a container URL param, not concat)
            .withUrlParam("stringtype", "unspecified");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
    }

    @Autowired
    private EvidenceService evidenceService;

    @Test
    void fullChainRoundTrips() throws IOException {
        Run run = evidenceService.createRun(
                "S1", "anthropic", "claude-sonnet-5", "prompt-sha", "1",
                Map.of("temperature", 0.2, "max_tokens", 4096));
        assertThat(run.getId()).matches("\\d{8}-\\d{6}-[0-9a-f]{8}");
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING.name());

        // single-flight: a second createRun while RUNNING must refuse
        assertThatThrownBy(() -> evidenceService.createRun(
                "S1", "anthropic", "claude-sonnet-5", "prompt-sha", "1", Map.of()))
                .isInstanceOf(IllegalStateException.class);

        LoadReportDto loadDto = new LoadReportDto(
                "stock-petclinic", "2026-09-04", 189.3, 11358,
                new LatencyDto(812.4, 664.0, 2500.0, 4100.0, 8933.0),
                0.0, 1.0,
                new ThresholdsDto("FAIL", List.of("p(95)<500")));
        Path k6Summary = writeTemp("k6-summary", "k6-summary.json");
        LoadReport loadReport = evidenceService.createLoadReport(run.getId(), "baseline-1", loadDto, k6Summary);
        assertThat(loadReport.getId()).isNotNull();

        JfrReportDto jfrDto = new JfrReportDto(
                run.getId(), "baseline-1",
                Map.of("JavaMonitorEnter", new SignalSummaryDto(
                        71738, 12.0, 407.0, 900.0, 1490.0, "CRITICAL",
                        List.of("UrlJarFiles$Cache.get", "URLClassPath$JarLoader.getResource"))),
                null);
        Path jfrFile = writeTemp("fake-jfr-bytes", "profile.jfr");
        JfrReport jfrReport = evidenceService.createJfrReport(run.getId(), "baseline-1", jfrDto, jfrFile);
        assertThat(jfrReport.getJfrSha256()).hasSize(64);
        // artifact copied under evidence/artifacts/<run-id>/<label>/ + sha matches content
        assertThat(jfrReport.getJfrPath()).contains(run.getId()).contains("baseline-1");
        assertThat(Files.exists(Path.of(jfrReport.getJfrPath()))).isTrue();
        assertThat(jfrReport.getJfrSha256())
                .isEqualTo(sha256Hex(Files.readAllBytes(Path.of(jfrReport.getJfrPath()))));

        Iteration iteration = evidenceService.createIteration(
                run.getId(), 1,
                new HypothesisDto("H5", 0.8, "fat-jar classloader lock dominates"),
                Map.of("H5", 2.0, "H3", -1.0),   // ledger — now Map<String,Object>
                new ChangeDto("template", null, "jar-unpack", Map.of()),
                "KEPT", "abc1234567890abcdef1234567890abcdef12345",
                loadReport.getId(), jfrReport.getId(),
                List.of(new FilesTouchedDto("Dockerfile.target", 12, 18)),
                "MECHANISM",
                "UrlJarFiles$Cache 71k→0 events, lock rate −81%, but p95 +24%");
        assertThat(iteration.getId()).isNotNull();

        TrajectoryEvent event = evidenceService.createTrajectoryEvent(
                run.getId(), "TOOL_CALL",
                Map.of("tool", "runBenchmark", "args", "{}"),
                1520L, 310L, new BigDecimal("0.004512"));
        assertThat(event.getTs()).isNotNull();

        // state machine: RUNNING → COMPLETED
        evidenceService.transitionRunStatus(run.getId(), RunStatus.RUNNING, RunStatus.COMPLETED);
        assertThatThrownBy(() -> evidenceService.transitionRunStatus(
                run.getId(), RunStatus.RUNNING, RunStatus.ABORTED))
                .isInstanceOf(IllegalStateException.class);
    }

    private static Path writeTemp(String content, String fileName) throws IOException {
        Path dir = Files.createTempDirectory("evidence-test");
        Path file = dir.resolve(fileName);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static String sha256Hex(byte[] bytes) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of().formatHex(digest.digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }
}
