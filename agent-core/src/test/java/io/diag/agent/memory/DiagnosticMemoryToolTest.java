package io.diag.agent.memory;

import io.diag.agent.ToolEnvelope;
import io.diag.agent.memory.impl.DiagnosticMemoryRetrieverImpl;
import io.diag.agent.memory.impl.IterationDocumentFactoryImpl;
import io.diag.agent.memory.impl.MemoryContextFormatterImpl;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone M3 — DiagnosticTools.queryPastFindings & 5 Essential Metadata Query Guards verification.
 */
class DiagnosticMemoryToolTest {

    @TempDir
    Path tempDir;

    private VectorStore vectorStore;
    private IterationDocumentFactory docFactory;
    private MemoryContextFormatter formatter;

    @BeforeEach
    void setUp() {
        vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        docFactory = new IterationDocumentFactoryImpl(MemoryConfig.defaults());
        formatter = new MemoryContextFormatterImpl();

        // Seed diverse historical records
        seedRecord("run-1", 1, "petclinic-s1", "H5", "jar-unpack", "KEPT",
                "JavaMonitorEnter lock contention on JarFile classloader",
                "Unpacked fat jar eliminated classloader lock.");
        seedRecord("run-2", 1, "petclinic-s2", "H5", "hikari-pool-size", "REVERTED",
                "JavaMonitorEnter lock contention on synchronized cache",
                "Pool size did not resolve monitor lock.");
        seedRecord("run-3", 1, "petclinic-s3", "H3", "jvm-opts", "KEPT",
                "GCPhasePause GC pause starvation and heap pressure",
                "Increased heap reduced young gen GC pause.");
    }

    @Test
    void hybridSearch_filtersBySymptomAndOutcome() {
        DiagnosticTools tools = createTools(
                new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1),
                "current-app");

        ToolEnvelope<String> env = tools.queryPastFindings("lock contention", null, null, "KEPT");

        assertThat(env.ok()).isTrue();
        assertThat(env.data())
                .contains("[PROVEN PATTERN - Other Service: petclinic-s1]")
                .contains("jar-unpack")
                .doesNotContain("petclinic-s2") // REVERTED excluded
                .doesNotContain("petclinic-s3"); // GC excluded by similarity threshold
    }

    @Test
    void guard4_pureMetadataAudit_usesZeroSimilarityThresholdWithoutDropouts() {
        DiagnosticTools tools = createTools(
                new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1),
                "current-app");

        // symptomQuery is null -> pure metadata query on category=H3, outcome=KEPT
        ToolEnvelope<String> env = tools.queryPastFindings(null, "  h3 ", null, " kept ");

        assertThat(env.ok()).isTrue();
        assertThat(env.data())
                .contains("petclinic-s3")
                .contains("jvm-opts")
                .doesNotContain("petclinic-s1");
    }

    @Test
    void guard1_evalIsolation_enforcesServerSideCurrentTargetExclusion() {
        // Evaluation mode = true, currentTargetId = "petclinic-s1"
        DiagnosticTools evalTools = createTools(
                new MemoryConfig(true, true, "text-embedding-3-small", 0.70, 3, 1),
                "petclinic-s1");

        ToolEnvelope<String> env = evalTools.queryPastFindings("lock contention", null, null, null);

        assertThat(env.ok()).isTrue();
        // Must exclude petclinic-s1 and only return petclinic-s2
        assertThat(env.data())
                .doesNotContain("petclinic-s1")
                .contains("[OBSERVATION - Other Service: petclinic-s2]");
    }

    @Test
    void guard2_whitelistValidation_rejectsHallucinatedCategoryAndOutcome() {
        DiagnosticTools tools = createTools(MemoryConfig.defaults(), "petclinic-s1");

        ToolEnvelope<String> badCategory = tools.queryPastFindings("lock", "database", null, null);
        assertThat(badCategory.ok()).isFalse();
        assertThat(badCategory.error())
                .contains("Invalid category: 'database'")
                .contains("H1..H7");

        ToolEnvelope<String> badOutcome = tools.queryPastFindings("lock", "H5", null, "FAILED");
        assertThat(badOutcome.ok()).isFalse();
        assertThat(badOutcome.error())
                .contains("Invalid outcome: 'FAILED'")
                .contains("Must be 'KEPT' or 'REVERTED'");
    }

    @Test
    void guard3_allNullOrBlankPrevention_rejectsEmptySearchCriteria() {
        DiagnosticTools tools = createTools(MemoryConfig.defaults(), "petclinic-s1");

        ToolEnvelope<String> allNull = tools.queryPastFindings(null, null, null, null);
        assertThat(allNull.ok()).isFalse();
        assertThat(allNull.error()).contains("At least one search criterion must be provided");

        ToolEnvelope<String> allBlank = tools.queryPastFindings("   ", " ", "", "  ");
        assertThat(allBlank.ok()).isFalse();
        assertThat(allBlank.error()).contains("At least one search criterion must be provided");
    }

    @Test
    void guard5_lengthCapsAndSanitization_failsFastOnOversizedInputs() {
        DiagnosticTools tools = createTools(MemoryConfig.defaults(), "petclinic-s1");

        String over400 = "x".repeat(401);
        ToolEnvelope<String> longSymptom = tools.queryPastFindings(over400, null, null, null);
        assertThat(longSymptom.ok()).isFalse();
        assertThat(longSymptom.error())
                .contains("symptomQuery exceeds 400 character limit (401 chars)");

        String over50Template = "t".repeat(51);
        ToolEnvelope<String> longTemplate = tools.queryPastFindings("lock", null, over50Template, null);
        assertThat(longTemplate.ok()).isFalse();
        assertThat(longTemplate.error())
                .contains("template parameter exceeds maximum length of 50 characters");
    }

    @Test
    void disabledMemoryConfig_returnsDisabledMessageWithoutQuerying() {
        MemoryConfig disabledConfig = new MemoryConfig(false, false, "text-embedding-3-small", 0.70, 3, 1);
        DiagnosticTools tools = createTools(disabledConfig, "petclinic-s1");

        ToolEnvelope<String> env = tools.queryPastFindings("lock contention", null, null, null);

        assertThat(env.ok()).isTrue();
        assertThat(env.data()).isEqualTo("Episodic memory is disabled for this run.");
    }

    @Test
    void noMatchingRecords_returnsDescriptiveEmptyResultMessage() {
        DiagnosticTools tools = createTools(MemoryConfig.defaults(), "petclinic-s1");

        ToolEnvelope<String> env = tools.queryPastFindings(null, "H7", "non-existent-template", "KEPT");

        assertThat(env.ok()).isTrue();
        assertThat(env.data())
                .contains("No matching past diagnostic runs found for criteria:")
                .contains("category=H7")
                .contains("template=non-existent-template")
                .contains("outcome=KEPT");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private DiagnosticTools createTools(MemoryConfig cfg, String currentTargetId) {
        DiagnosticMemoryRetriever retriever = new DiagnosticMemoryRetrieverImpl(cfg, vectorStore);
        return new DiagnosticTools(null, null, null, null, tempDir, currentTargetId, cfg, retriever, formatter);
    }

    private void seedRecord(String runId, int n, String targetId, String category,
                            String template, String outcome, String symptom, String finding) {
        Run run = Run.builder().id(runId).targetId(targetId).build();
        Iteration iter = Iteration.builder()
                .n(n)
                .hypothesis(new HypothesisDto(category, 0.90, "Hypothesis rationale"))
                .change(new ChangeDto("template", null, template, Map.of()))
                .outcome(outcome)
                .keepType("KEPT".equals(outcome) ? "P95" : "NONE")
                .finding(finding)
                .build();
        Document doc = docFactory.toDocument(iter, run, symptom);
        vectorStore.add(List.of(doc));
    }
}
