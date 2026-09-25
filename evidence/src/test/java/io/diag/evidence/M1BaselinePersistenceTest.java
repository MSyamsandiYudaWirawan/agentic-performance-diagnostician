package io.diag.evidence;

import io.diag.evidence.entity.Run;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.service.EvidenceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step-9 M1a gate: the run-row plumbing resume depends on (scope §10.19) —
 * recordBaseline/recordKeptSha persist the V2 columns, the status machine
 * survives update-saves, and a bogus runId fails with a message naming the run.
 *
 * The isNew trap this guards: a loaded Run still reports isNew() == true
 * (Lombok keeps the @Builder.Default field initializer), so an update-save
 * without setIsNew(false) INSERTs an existing PK and blows up in here.
 */
// artifacts-root pinned under target/ — the default is relative and would
// litter the module tree (evidence/evidence/artifacts) on every test run
@SpringBootTest(properties = "evidence.artifacts-root=target/test-artifacts")
@Testcontainers
class M1BaselinePersistenceTest {

    // sha-shaped constants, 40 hex chars each
    private static final String ORIGIN_SHA = "0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c";
    private static final String KEPT_SHA_1 = "1111111111111111111111111111111111111111";
    private static final String KEPT_SHA_2 = "2222222222222222222222222222222222222222";

    // spec 5.3 ground-truth fixture numbers (REF h3: rps 189, p95 2500ms;
    // floors at 5% of median → 9.45 / 125)
    private static final Double BASELINE_P95_MS = 2500.0;
    private static final Double NOISE_FLOOR_MS = 125.0;
    private static final Double BASELINE_RPS = 189.3;
    private static final Double NOISE_FLOOR_RPS = 9.45;

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

    @Autowired
    private RunRepository runRepository;

    @AfterEach
    void releaseSingleFlightLock() {
        // tests commit for real (no @Transactional rollback here) and createRun
        // enforces single-flight — finish whatever this test left RUNNING so the
        // next test's failure, if any, is its own
        runRepository.findFirstByStatus(RunStatus.RUNNING.name())
                .ifPresent(r -> evidenceService.transitionRunStatus(
                        r.getId(), RunStatus.RUNNING, RunStatus.COMPLETED));
    }

    @Test
    void recordBaselinePersistsResumeStateWithoutTouchingStatus() {
        Run run = evidenceService.createRun(
                "S1", "anthropic", "glm-5.3", "prompt-sha", "1", Map.of());

        evidenceService.recordBaseline(run.getId(),
                BASELINE_P95_MS, NOISE_FLOOR_MS, BASELINE_RPS, NOISE_FLOOR_RPS, ORIGIN_SHA);

        Run reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getBaselineP95Ms()).isEqualTo(BASELINE_P95_MS);
        assertThat(reloaded.getNoiseFloorMs()).isEqualTo(NOISE_FLOOR_MS);
        assertThat(reloaded.getBaselineRps()).isEqualTo(BASELINE_RPS);
        assertThat(reloaded.getNoiseFloorRps()).isEqualTo(NOISE_FLOOR_RPS);
        assertThat(reloaded.getOriginSha()).isEqualTo(ORIGIN_SHA);
        // last_kept_sha is recordKeptSha's column alone — still unset here
        assertThat(reloaded.getLastKeptSha()).isNull();
        // resume reads these to decide "killed during baselining" — the update
        // must not disturb the lifecycle columns
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.RUNNING.name());
        assertThat(reloaded.getFinishedAt()).isNull();
    }

    @Test
    void recordKeptShaOverwritesCleanly() {
        Run run = evidenceService.createRun(
                "S1", "anthropic", "glm-5.3", "prompt-sha", "1", Map.of());

        evidenceService.recordKeptSha(run.getId(), KEPT_SHA_1);
        evidenceService.recordKeptSha(run.getId(), KEPT_SHA_2);

        assertThat(runRepository.findById(run.getId()).orElseThrow().getLastKeptSha())
                .isEqualTo(KEPT_SHA_2);
    }

    @Test
    void unknownRunIdThrowsMessageNamingTheRun() {
        String bogus = "20990101-000000-deadbeef";
        assertThatThrownBy(() -> evidenceService.recordBaseline(
                bogus, BASELINE_P95_MS, NOISE_FLOOR_MS, BASELINE_RPS, NOISE_FLOOR_RPS, ORIGIN_SHA))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(bogus);
        assertThatThrownBy(() -> evidenceService.recordKeptSha(bogus, KEPT_SHA_1))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(bogus);
    }

    @Test
    void updatesDoNotBreakTheStatusMachine() {
        Run run = evidenceService.createRun(
                "S1", "anthropic", "glm-5.3", "prompt-sha", "1", Map.of());
        evidenceService.recordBaseline(run.getId(),
                BASELINE_P95_MS, NOISE_FLOOR_MS, BASELINE_RPS, NOISE_FLOOR_RPS, ORIGIN_SHA);
        evidenceService.recordKeptSha(run.getId(), KEPT_SHA_1);

        // the trap: without setIsNew(false) the update-save INSERTs an existing
        // PK — recordBaseline/recordKeptSha throw above, and a broken row fails
        // the conditional transition here
        evidenceService.transitionRunStatus(run.getId(), RunStatus.RUNNING, RunStatus.COMPLETED);

        Run reloaded = runRepository.findById(run.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(reloaded.getFinishedAt()).isNotNull();
        assertThat(reloaded.getLastKeptSha()).isEqualTo(KEPT_SHA_1);
        assertThat(reloaded.getBaselineRps()).isEqualTo(BASELINE_RPS);
    }
}
