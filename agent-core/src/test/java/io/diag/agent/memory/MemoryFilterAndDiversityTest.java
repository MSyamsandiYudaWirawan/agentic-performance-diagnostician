package io.diag.agent.memory;

import io.diag.agent.memory.impl.DiagnosticMemoryRetrieverImpl;
import io.diag.agent.memory.impl.IterationDocumentFactoryImpl;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone M2 — Dual-Mode Filter (Eval vs Production) & Diversity Verification.
 */
class MemoryFilterAndDiversityTest {

    private VectorStore vectorStore;
    private IterationDocumentFactory docFactory;

    @BeforeEach
    void setUp() {
        vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        MemoryConfig baseConfig = MemoryConfig.defaults();
        docFactory = new IterationDocumentFactoryImpl(baseConfig);
    }

    @Test
    void evaluationMode_excludesCurrentTargetIdStrictZeroLeakage() {
        // Seed store with lock findings across petclinic-s1, petclinic-s2, and practice-mvc-s4
        addMemory("run-1", 1, "petclinic-s1", "H5", "jar-unpack", "KEPT",
                "JavaMonitorEnter lock contention on JarFile");
        addMemory("run-2", 1, "petclinic-s2", "H5", "custom-edit", "KEPT",
                "JavaMonitorEnter lock contention on synchronized cache");
        addMemory("run-3", 1, "practice-mvc-s4", "H5", "custom-edit", "REVERTED",
                "JavaMonitorEnter lock contention on monitor");

        MemoryConfig evalConfig = new MemoryConfig(true, true, "text-embedding-3-small", 0.70, 3, 1);
        DiagnosticMemoryRetriever evalRetriever = new DiagnosticMemoryRetrieverImpl(evalConfig, vectorStore);

        List<Document> results = evalRetriever.retrieve(
                new MemoryQuery("JavaMonitorEnter lock contention", null, null, null),
                "petclinic-s1");

        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(d -> d.getMetadata().get("targetId"))
                .doesNotContain("petclinic-s1")
                .containsExactlyInAnyOrder("petclinic-s2", "practice-mvc-s4");
    }

    @Test
    void productionMode_includesCurrentTargetIdAlongsideOtherTargets() {
        addMemory("run-1", 1, "petclinic-s1", "H5", "jar-unpack", "KEPT",
                "JavaMonitorEnter lock contention on JarFile");
        addMemory("run-2", 1, "practice-mvc-s4", "H5", "custom-edit", "KEPT",
                "JavaMonitorEnter lock contention on monitor");

        MemoryConfig prodConfig = new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1);
        DiagnosticMemoryRetriever prodRetriever = new DiagnosticMemoryRetrieverImpl(prodConfig, vectorStore);

        List<Document> results = prodRetriever.retrieve(
                new MemoryQuery("JavaMonitorEnter lock contention", null, null, null),
                "petclinic-s1");

        assertThat(results).hasSize(2);
        assertThat(results)
                .extracting(d -> d.getMetadata().get("targetId"))
                .containsExactlyInAnyOrder("petclinic-s1", "practice-mvc-s4");
    }

    @Test
    void diversityConstraint_deduplicatesByTargetIdAndTemplatePair() {
        // Insert 3 iterations all sharing (targetId="petclinic-s1", template="hikari-pool-size")
        addMemory("run-1", 1, "petclinic-s1", "H2", "hikari-pool-size", "REVERTED",
                "ThreadPark Hikari pool connection timeout attempt 1");
        addMemory("run-1", 2, "petclinic-s1", "H2", "hikari-pool-size", "REVERTED",
                "ThreadPark Hikari pool connection timeout attempt 2");
        addMemory("run-2", 1, "petclinic-s1", "H2", "hikari-pool-size", "REVERTED",
                "ThreadPark Hikari pool connection timeout attempt 3");

        // Plus 1 distinct template on same target, and 1 on another target
        addMemory("run-2", 2, "petclinic-s1", "H2", "custom-edit", "KEPT",
                "ThreadPark Hikari pool connection timeout fixed via custom edit");
        addMemory("run-3", 1, "petclinic-s2", "H2", "hikari-pool-size", "KEPT",
                "ThreadPark Hikari pool connection timeout on s2");

        MemoryConfig prodConfig = new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1);
        DiagnosticMemoryRetriever prodRetriever = new DiagnosticMemoryRetrieverImpl(prodConfig, vectorStore);

        List<Document> results = prodRetriever.retrieve(
                new MemoryQuery("ThreadPark Hikari pool connection timeout", null, null, null),
                "petclinic-s1");

        // Must deduplicate the 3 (petclinic-s1, hikari-pool-size) records down to 1, returning 3 diverse results total
        assertThat(results).hasSize(3);

        long s1HikariCount = results.stream()
                .filter(d -> "petclinic-s1".equals(d.getMetadata().get("targetId"))
                        && "hikari-pool-size".equals(d.getMetadata().get("template")))
                .count();
        assertThat(s1HikariCount).isEqualTo(1);
    }

    private void addMemory(String runId, int n, String targetId, String category,
                           String template, String outcome, String symptomText) {
        Run run = Run.builder().id(runId).targetId(targetId).build();
        Iteration iter = Iteration.builder()
                .n(n)
                .hypothesis(new HypothesisDto(category, 0.80, "Test rationale"))
                .change("custom-edit".equals(template)
                        ? new ChangeDto("edits", List.of(), null, null)
                        : new ChangeDto("template", null, template, Map.of()))
                .outcome(outcome)
                .keepType("KEPT".equals(outcome) ? "RPS" : "NONE")
                .finding("Finding for " + runId + ":" + n)
                .build();
        Document doc = docFactory.toDocument(iter, run, symptomText);
        vectorStore.add(List.of(doc));
    }
}
