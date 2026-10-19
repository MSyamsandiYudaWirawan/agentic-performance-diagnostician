package io.diag.agent.memory;

import io.diag.agent.memory.impl.DiagnosticMemoryRetrieverImpl;
import io.diag.agent.memory.impl.IterationDocumentFactoryImpl;
import io.diag.agent.memory.impl.IterationIndexerImpl;
import io.diag.agent.memory.impl.SymptomSummarizerImpl;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.dto.ThresholdsDto;
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
 * Milestone M1 — Vector Ingestion & Basic Retrieval using offline SimpleVectorStore.
 */
class DiagnosticMemoryRetrieverTest {

    private VectorStore vectorStore;
    private MemoryConfig config;
    private SymptomSummarizer summarizer;
    private IterationDocumentFactory docFactory;
    private IterationIndexer indexer;
    private DiagnosticMemoryRetriever retriever;

    @BeforeEach
    void setUp() {
        vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        config = new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1);
        summarizer = new SymptomSummarizerImpl();
        docFactory = new IterationDocumentFactoryImpl(config);
        indexer = new IterationIndexerImpl(config, summarizer, docFactory, vectorStore);
        retriever = new DiagnosticMemoryRetrieverImpl(config, vectorStore);
    }

    @Test
    void ingestAndRetrieve_lockAndGcQueries_matchExpectedTopCategories() {
        // S1: H5 lock contention on JarFile
        ingestSynthetic("run-s1", "petclinic-s1", 1, "H5", "jar-unpack", "KEPT", "P95",
                "JavaMonitorEnter", "java.util.jar.JarFile.getBytes",
                "Unpacking fat jar eliminated classloader lock contention.");

        // S2: H2 Hikari connection pool wait
        ingestSynthetic("run-s2", "petclinic-s2", 1, "H2", "hikari-pool-size", "KEPT", "RPS",
                "ThreadPark", "com.zaxxer.hikari.pool.HikariPool.getConnection",
                "Increasing Hikari pool size resolved connection wait.");

        // S3: H3 GC pause starvation
        ingestSynthetic("run-s3", "petclinic-s3", 1, "H3", "jvm-opts", "KEPT", "P95",
                "GCPhasePause", "GcPause.youngGenCollection",
                "Tuning heap size reduced GC pause starvation.");

        // 1. Query with JFR lock symptom -> top result is H5 lock iteration
        List<Document> lockResults = retriever.retrieve(
                new MemoryQuery("JavaMonitorEnter lock contention on JarFile classloader", null, null, null),
                "current-service");

        assertThat(lockResults).isNotEmpty();
        assertThat(lockResults.get(0).getMetadata())
                .containsEntry("category", "H5")
                .containsEntry("targetId", "petclinic-s1")
                .containsEntry("template", "jar-unpack");

        // 2. Query with GC starvation symptom -> top result is H3 GC iteration
        List<Document> gcResults = retriever.retrieve(
                new MemoryQuery("GCPhasePause GC pause starvation and heap pressure", null, null, null),
                "current-service");

        assertThat(gcResults).isNotEmpty();
        assertThat(gcResults.get(0).getMetadata())
                .containsEntry("category", "H3")
                .containsEntry("targetId", "petclinic-s3")
                .containsEntry("template", "jvm-opts");
    }

    @Test
    void embeddingModelFilter_excludesVectorsFromMismatchedModel() {
        // Index one document under an older/different embedding model ("nomic-embed-text")
        MemoryConfig oldModelConfig = new MemoryConfig(true, false, "nomic-embed-text", 0.55, 3, 1);
        IterationIndexer oldIndexer = new IterationIndexerImpl(
                oldModelConfig, summarizer, new IterationDocumentFactoryImpl(oldModelConfig), vectorStore);

        Run oldRun = Run.builder().id("run-old").targetId("petclinic-s1").build();
        Iteration oldIter = Iteration.builder()
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.9, "lock contention"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("KEPT")
                .keepType("RPS")
                .finding("Old model finding")
                .build();
        oldIndexer.index(oldIter, oldRun, sampleLoad(), sampleJfr("JavaMonitorEnter", "JarFile.getBytes"));

        // Retriever configured with "text-embedding-3-small" must exclude "nomic-embed-text" document
        List<Document> results = retriever.retrieve(
                new MemoryQuery("JavaMonitorEnter lock contention on JarFile", null, null, null),
                "other-service");

        assertThat(results).isEmpty();
    }

    @Test
    void iterationIndexer_skipsWastedIterationsUnconditionally() {
        Run run = Run.builder().id("run-wasted").targetId("petclinic-s1").build();
        Iteration wasted = Iteration.builder()
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.8, "lock contention"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("WASTED")
                .finding("invalid decision")
                .build();

        indexer.index(wasted, run, sampleLoad(), sampleJfr("JavaMonitorEnter", "JarFile.getBytes"));

        List<Document> results = retriever.retrieve(
                new MemoryQuery("JavaMonitorEnter lock contention", null, null, null),
                "other-service");

        assertThat(results).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void ingestSynthetic(String runId, String targetId, int n, String category,
                                 String template, String outcome, String keepType,
                                 String signalName, String topFrame, String finding) {
        Run run = Run.builder().id(runId).targetId(targetId).build();
        Iteration iter = Iteration.builder()
                .n(n)
                .hypothesis(new HypothesisDto(category, 0.85, "Hypothesis for " + signalName))
                .change(new ChangeDto("template", null, template, Map.of()))
                .outcome(outcome)
                .keepType(keepType)
                .finding(finding)
                .build();
        indexer.index(iter, run, sampleLoad(), sampleJfr(signalName, topFrame));
    }

    private LoadReportDto sampleLoad() {
        return new LoadReportDto(
                "test-repo", "2026-10-19T00:00:00Z",
                189.0, 10000L,
                new LatencyDto(1000.0, 900.0, 2400.0, 3000.0, 4000.0),
                0.0, 100.0,
                new ThresholdsDto("FAIL", List.of("http_req_duration"))
        );
    }

    private JfrReportDto sampleJfr(String signal, String frame) {
        return new JfrReportDto("run-1", "iter-1", Map.of(
                signal, new SignalSummaryDto(50000L, 10.0, 40.0, 80.0, 150.0, "CRITICAL", List.of(frame))
        ), null);
    }
}
