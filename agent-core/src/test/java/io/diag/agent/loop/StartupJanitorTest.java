package io.diag.agent.loop;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M1b pure half of the janitor: classification only, no docker. The docker
 * half (compose ls / down -v / rm fallback) is hand-verified once per the M1b
 * briefing — it cannot be honestly unit-tested.
 */
class StartupJanitorTest {

    private static final String ACTIVE = "20260925-093000-1a2b3c4d";

    @Test
    void runIdPatternProjectsAreOrphans() {
        assertThat(StartupJanitor.orphans(
                List.of("20260924-101500-abcd1234", "20260101-000000-00000000"), ACTIVE))
                .containsExactly("20260924-101500-abcd1234", "20260101-000000-00000000");
    }

    @Test
    void diagEvidenceIsNeverTouched() {
        assertThat(StartupJanitor.orphans(List.of("diag-evidence"), ACTIVE)).isEmpty();
    }

    @Test
    void activeRunsProjectIsSpared() {
        assertThat(StartupJanitor.orphans(List.of(ACTIVE), ACTIVE)).isEmpty();
    }

    @Test
    void nonRunIdProjectsAreIgnored() {
        assertThat(StartupJanitor.orphans(
                List.of("my-dev-stack", "petclinic", "2026-run-xyz"), ACTIVE)).isEmpty();
    }

    @Test
    void freshStartNullActiveRunClassifiesAllRunIdProjectsAsOrphans() {
        // fresh start has no active run yet — only the evidence DB is spared
        assertThat(StartupJanitor.orphans(
                List.of("20260924-101500-abcd1234", "diag-evidence"), null))
                .containsExactly("20260924-101500-abcd1234");
    }
}
