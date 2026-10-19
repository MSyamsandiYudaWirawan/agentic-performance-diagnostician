package io.diag.agent.memory;

import io.diag.agent.memory.impl.MemoryContextFormatterImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone M3 — MemoryContextFormatter 2x2 Evidence Advisory Matrix verification.
 */
class MemoryContextFormatterTest {

    private MemoryContextFormatter formatter;

    @BeforeEach
    void setUp() {
        formatter = new MemoryContextFormatterImpl();
    }

    @Test
    void formatForPrompt_outputsAllFourAdvisoryQuadrants() {
        Document sameReverted = Document.builder()
                .id("1")
                .text("SYMPTOM: Lock wait\nPROPOSAL: hikari-pool-size\nOUTCOME: REVERTED (KeepType: NONE)\nFINDING: Did not help")
                .metadata(Map.of("targetId", "petclinic", "outcome", "REVERTED"))
                .build();

        Document otherReverted = Document.builder()
                .id("2")
                .text("SYMPTOM: Lock wait\nPROPOSAL: db-fetch-size\nOUTCOME: REVERTED (KeepType: NONE)\nFINDING: Caused pressure")
                .metadata(Map.of("targetId", "petclinic-s2", "outcome", "REVERTED"))
                .build();

        Document sameKept = Document.builder()
                .id("3")
                .text("SYMPTOM: Lock wait\nPROPOSAL: jar-unpack\nOUTCOME: KEPT (KeepType: P95)\nFINDING: Eliminated 81% lock wait")
                .metadata(Map.of("targetId", "petclinic", "outcome", "KEPT"))
                .build();

        Document otherKept = Document.builder()
                .id("4")
                .text("SYMPTOM: Lock wait\nPROPOSAL: custom-edit\nOUTCOME: KEPT (KeepType: RPS)\nFINDING: Reduced synchronized scope")
                .metadata(Map.of("targetId", "practice-mvc-s4", "outcome", "KEPT"))
                .build();

        String formatted = formatter.formatForPrompt(
                List.of(sameReverted, otherReverted, sameKept, otherKept),
                "petclinic"
        );

        assertThat(formatted)
                .startsWith("=== EPISODIC MEMORY (RELEVANT PAST EXPERIENCES) ===")
                // 1. Same-target + REVERTED -> CAUTION - Same Service
                .contains("[Experience 1] [CAUTION - Same Service: petclinic]")
                .contains("Attempted and REVERTED on this service earlier. Strongly discouraged unless you have clear evidence that the underlying mechanism differs.")
                // 2. Cross-target + REVERTED -> OBSERVATION - Other Service
                .contains("[Experience 2] [OBSERVATION - Other Service: petclinic-s2]")
                .contains("Reverted on petclinic-s2. Informative context: verify if your bottleneck shares the same root cause before attempting.")
                // 3. Same-target + KEPT -> BASELINE - Same Service
                .contains("[Experience 3] [BASELINE - Same Service: petclinic]")
                .contains("This fix was already KEPT in an earlier run and is active in your baseline. Verify before proposing again.")
                // 4. Cross-target + KEPT -> PROVEN PATTERN - Other Service
                .contains("[Experience 4] [PROVEN PATTERN - Other Service: practice-mvc-s4]")
                .contains("Succeeded on practice-mvc-s4 for similar symptoms. High-confidence candidate to investigate.")
                // Bullet structure check
                .contains("- SYMPTOM: Lock wait")
                .contains("- PROPOSAL: jar-unpack")
                .contains("- OUTCOME: KEPT (KeepType: P95)")
                .contains("- FINDING: Eliminated 81% lock wait");
    }

    @Test
    void formatForPrompt_emptyOrNullMemories_returnsEmptyString() {
        assertThat(formatter.formatForPrompt(List.of(), "petclinic")).isEmpty();
        assertThat(formatter.formatForPrompt(null, "petclinic")).isEmpty();
    }
}
