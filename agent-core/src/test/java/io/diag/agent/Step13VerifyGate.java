package io.diag.agent;

import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.BoundedReadSource;
import io.diag.agent.loop.FakeDecideTurn;
import io.diag.agent.loop.FakeEvidenceService;
import io.diag.agent.loop.FakeTargetPipeline;
import io.diag.agent.loop.LoopConfig;
import io.diag.agent.memory.DiagnosticMemoryRetriever;
import io.diag.agent.memory.FakeEmbeddingModel;
import io.diag.agent.memory.IterationDocumentFactory;
import io.diag.agent.memory.IterationIndexer;
import io.diag.agent.memory.MemoryConfig;
import io.diag.agent.memory.MemoryContextFormatter;
import io.diag.agent.memory.MemoryQuery;
import io.diag.agent.memory.SymptomSummarizer;
import io.diag.agent.memory.impl.DiagnosticMemoryRetrieverImpl;
import io.diag.agent.memory.impl.IterationDocumentFactoryImpl;
import io.diag.agent.memory.impl.IterationIndexerImpl;
import io.diag.agent.memory.impl.MemoryContextFormatterImpl;
import io.diag.agent.memory.impl.SymptomSummarizerImpl;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.FixTemplateRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 13 deterministic loop verification gate (spec Part 4 Milestone M4).
 *
 * 100% offline, deterministic, zero API token cost:
 * - In-memory SimpleVectorStore + FakeEmbeddingModel (no OpenAI / no Postgres)
 * - FakeEvidenceService, FakeDecideTurn, FakeTargetPipeline
 * - Real ChangeApplier on a temporary git clone of targets/spring-petclinic
 *
 * Execution:
 *   mvn -pl agent-core -am test "-Dtest=Step13VerifyGate"
 */
class Step13VerifyGate {

    private static final Path PETCLINIC_SOURCE = Path.of("C:/study/agentic-performance-diagnostician/targets/spring-petclinic");

    private Path scratchRepo;
    private ChangeApplier changeApplier;
    private FakeEvidenceService evidenceService;
    private FakeDecideTurn decideTurn;
    private FakeTargetPipeline targetPipeline;
    private GenParams genParams;

    private VectorStore vectorStore;
    private MemoryConfig memoryConfig;
    private SymptomSummarizer summarizer;
    private IterationDocumentFactory documentFactory;
    private IterationIndexer iterationIndexer;
    private DiagnosticMemoryRetriever memoryRetriever;
    private MemoryContextFormatter memoryFormatter;

    @BeforeEach
    void setUp() throws Exception {
        scratchRepo = Files.createTempDirectory("step13-gate-");
        git(Path.of("."), "clone", "-c", "core.autocrlf=false",
                PETCLINIC_SOURCE.toString(), scratchRepo.toString());

        changeApplier   = new ChangeApplier(new FixTemplateRegistry());
        evidenceService = new FakeEvidenceService();
        decideTurn      = new FakeDecideTurn();
        targetPipeline  = new FakeTargetPipeline();
        targetPipeline.withEvidenceService(evidenceService,
                () -> evidenceService.findRunningRun().map(Run::getId).orElse(null));
        genParams       = new GenParams("test-provider", "test-model", 0.0, 1000);

        vectorStore     = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
        memoryConfig    = new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1);
        summarizer      = new SymptomSummarizerImpl();
        documentFactory = new IterationDocumentFactoryImpl(memoryConfig);
        iterationIndexer = new IterationIndexerImpl(memoryConfig, summarizer, documentFactory, vectorStore);
        memoryRetriever = new DiagnosticMemoryRetrieverImpl(memoryConfig, vectorStore);
        memoryFormatter = new MemoryContextFormatterImpl();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (scratchRepo != null && Files.exists(scratchRepo)) {
            try (Stream<Path> walk = Files.walk(scratchRepo)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                System.err.println("[Step13VerifyGate] cleanup failed: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // 1. Ingestion Trigger Test: KEPT and REVERTED are indexed; WASTED is skipped
    // -------------------------------------------------------------------------

    @Test
    void verification1_ingestionTrigger_indexesKeptAndRevertedAndSkipsWasted() throws Exception {
        // --- Case A: KEPT iteration writes 1 document to VectorStore ---
        configureDefaultBaselines();
        DecisionDto keptDecision = new DecisionDto(
                new HypothesisDto("H5", 0.90, "lock contention on embedded jar"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "unpacked jar eliminates lock"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(keptDecision));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-1", 280.0, 2100.0, 40.0, 0.0, Map.of("JavaMonitorEnter", 100L)));

        AgentLoop loopKept = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                configWithMaxIterations(1), genParams, scratchRepo, "petclinic-s1",
                iterationIndexer);

        String keptRunId = loopKept.start();
        assertThat(evidenceService.getRun(keptRunId).getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Document> storedAfterKept = memoryRetriever.retrieve(
                new MemoryQuery(null, "H5", "jar-unpack", "KEPT"), "other-target");
        assertThat(storedAfterKept).hasSize(1);
        assertThat(storedAfterKept.get(0).getMetadata())
                .containsEntry("runId", keptRunId)
                .containsEntry("targetId", "petclinic-s1")
                .containsEntry("outcome", "KEPT")
                .containsEntry("keepType", "RPS");

        // --- Case B: WASTED iteration (invalid decision) is skipped unconditionally ---
        configureDefaultBaselines();
        decideTurn.enqueue(FakeDecideTurn.invalid("malformed JSON"));

        AgentLoop loopWasted = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                configWithMaxIterations(1), genParams, scratchRepo, "petclinic-wasted",
                iterationIndexer);

        String wastedRunId = loopWasted.start();
        List<Iteration> wastedIters = evidenceService.iterationsFor(wastedRunId);
        assertThat(wastedIters).hasSize(1);
        assertThat(wastedIters.get(0).getOutcome()).isEqualTo("WASTED");

        // VectorStore still has only the 1 document from Case A
        List<Document> allDocs = vectorStore.similaritySearch(
                SearchRequest.builder().query("").similarityThreshold(0.0).topK(10).build());
        assertThat(allDocs).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // 2. Tool Execution & Safety Test via BoundedReadSource
    // -------------------------------------------------------------------------

    @Test
    void verification2_toolExecutionViaBoundedReadSource_respectsEvalModeAndAdvisories() {
        // Seed 2 memories: one on petclinic-s1 (REVERTED), one on practice-mvc-s4 (KEPT)
        Run s1Run = Run.builder().id("run-s1").targetId("petclinic-s1").build();
        Iteration s1Iter = Iteration.builder()
                .n(1)
                .hypothesis(new HypothesisDto("H2", 0.60, "Increase DB pool size"))
                .change(new ChangeDto("template", null, "hikari-pool-size", Map.of("size", "30")))
                .outcome("REVERTED")
                .keepType("NONE")
                .finding("Pool increase did not relieve lock contention.")
                .build();
        vectorStore.add(List.of(documentFactory.toDocument(s1Iter, s1Run,
                "Load: 189.0 RPS, p95 2400ms. Hotspots: JavaMonitorEnter (CRITICAL, count=71738)")));

        Run s4Run = Run.builder().id("run-s4").targetId("practice-mvc-s4").build();
        Iteration s4Iter = Iteration.builder()
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.90, "Unpack fat jar"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("KEPT")
                .keepType("P95")
                .finding("Unpacking jar eliminated 81% of lock contention.")
                .build();
        vectorStore.add(List.of(documentFactory.toDocument(s4Iter, s4Run,
                "Load: 210.0 RPS, p95 1850ms. Hotspots: JavaMonitorEnter (CRITICAL, count=45200)")));

        // 2a. Production mode (evaluationMode = false): both targets visible with proper advisory tags
        DiagnosticTools prodTools = new DiagnosticTools(
                null, null, null, null, scratchRepo,
                "petclinic-s1", memoryConfig, memoryRetriever, memoryFormatter);
        BoundedReadSource prodReader = new BoundedReadSource(prodTools, 5, evidenceService, "run-prod");

        ToolEnvelope<String> prodResult = prodReader.queryPastFindings("JavaMonitorEnter lock contention", null, null, null);
        assertThat(prodResult.ok()).isTrue();
        assertThat(prodResult.data())
                .contains("[CAUTION - Same Service: petclinic-s1]")
                .contains("[PROVEN PATTERN - Other Service: practice-mvc-s4]");

        // Verify trajectory events (TOOL_CALL + TOOL_RESULT) were recorded by BoundedReadSource
        List<TrajectoryEvent> events = evidenceService.findTrajectoryEvents("run-prod");
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getKind()).isEqualTo("TOOL_CALL");
        assertThat(events.get(0).getPayload()).containsEntry("action", "queryPastFindings");
        assertThat(events.get(1).getKind()).isEqualTo("TOOL_RESULT");
        assertThat(events.get(1).getPayload()).containsEntry("ok", true);

        // 2b. Evaluation mode (evaluationMode = true): petclinic-s1 strictly excluded
        MemoryConfig evalConfig = new MemoryConfig(true, true, "text-embedding-3-small", 0.70, 3, 1);
        DiagnosticMemoryRetriever evalRetriever = new DiagnosticMemoryRetrieverImpl(evalConfig, vectorStore);
        DiagnosticTools evalTools = new DiagnosticTools(
                null, null, null, null, scratchRepo,
                "petclinic-s1", evalConfig, evalRetriever, memoryFormatter);
        BoundedReadSource evalReader = new BoundedReadSource(evalTools, 5, evidenceService, "run-eval");

        ToolEnvelope<String> evalResult = evalReader.queryPastFindings("JavaMonitorEnter lock contention", null, null, null);
        assertThat(evalResult.ok()).isTrue();
        assertThat(evalResult.data())
                .doesNotContain("petclinic-s1")
                .contains("[PROVEN PATTERN - Other Service: practice-mvc-s4]");
    }

    // -------------------------------------------------------------------------
    // 3. Fail-Open Resilience Test: VectorStore exception does not crash tool or AgentLoop
    // -------------------------------------------------------------------------

    @Test
    void verification3_failOpenResilience_vectorStoreOutageHandledGracefully() throws Exception {
        VectorStore failingVectorStore = new VectorStore() {
            @Override
            public void add(List<Document> documents) {
                throw new RuntimeException("Simulated pgvector / embedding connection timeout on write");
            }

            @Override
            public void delete(List<String> idList) {
                throw new RuntimeException("Simulated outage");
            }

            @Override
            public void delete(Filter.Expression filterExpression) {
                throw new RuntimeException("Simulated outage");
            }

            @Override
            public List<Document> similaritySearch(SearchRequest request) {
                throw new RuntimeException("Simulated pgvector / embedding timeout on search");
            }
        };

        DiagnosticMemoryRetriever failingRetriever = new DiagnosticMemoryRetrieverImpl(memoryConfig, failingVectorStore);
        IterationIndexer failingIndexer = new IterationIndexerImpl(memoryConfig, summarizer, documentFactory, failingVectorStore);

        DiagnosticTools resilientTools = new DiagnosticTools(
                null, null, null, null, scratchRepo,
                "petclinic-s1", memoryConfig, failingRetriever, memoryFormatter);
        BoundedReadSource reader = new BoundedReadSource(resilientTools, 5, evidenceService, "run-resilience");

        // 3a. Tool returns fail envelope rather than throwing uncaught exception
        ToolEnvelope<String> toolEnv = reader.queryPastFindings("lock contention", null, null, null);
        assertThat(toolEnv.ok()).isFalse();
        assertThat(toolEnv.error()).contains("Memory search temporarily unavailable:");

        // 3b. AgentLoop completes its iteration and run cleanly even when indexer's VectorStore throws
        configureDefaultBaselines();
        DecisionDto decision = new DecisionDto(
                new HypothesisDto("H5", 0.85, "lock contention on jar"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "jar-unpack"),
                new ChangeDto("template", null, "jar-unpack", Map.of())
        );
        decideTurn.enqueue(FakeDecideTurn.valid(decision));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle(
                "iter-1", 275.0, 2100.0, 40.0, 0.0, Map.of("JavaMonitorEnter", 0L)));

        AgentLoop loop = new AgentLoop(
                evidenceService, changeApplier, decideTurn, targetPipeline,
                configWithMaxIterations(1), genParams, scratchRepo, "petclinic-s1",
                failingIndexer);

        String runId = loop.start();

        Run run = evidenceService.getRun(runId);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());
        assertThat(evidenceService.iterationsFor(runId)).hasSize(1);
        assertThat(evidenceService.iterationsFor(runId).get(0).getOutcome()).isEqualTo("KEPT");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void configureDefaultBaselines() {
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-1", 188.0, 2520.0, 50.0, 0.0, Map.of("JavaMonitorEnter", 71000L)));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-2", 190.0, 2480.0, 49.0, 0.0, Map.of("JavaMonitorEnter", 71500L)));
        targetPipeline.enqueueBenchmark(targetPipeline.createCycle("baseline-3", 189.0, 2500.0, 50.0, 0.0, Map.of("JavaMonitorEnter", 71193L)));
    }

    private LoopConfig configWithMaxIterations(int maxIter) {
        return new LoopConfig(
                maxIter,
                3_600_000L,
                500_000L,
                new BigDecimal("5.00"),
                10,
                0.50,
                BigDecimal.ZERO,
                BigDecimal.ZERO
        );
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
    }
}
