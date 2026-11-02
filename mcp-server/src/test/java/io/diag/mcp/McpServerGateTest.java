package io.diag.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.memory.DiagnosticMemoryRetriever;
import io.diag.agent.memory.MemoryConfig;
import io.diag.agent.memory.MemoryContextFormatter;
import io.diag.agent.memory.MemoryQuery;
import io.diag.agent.memory.impl.DiagnosticMemoryRetrieverImpl;
import io.diag.agent.memory.impl.MemoryContextFormatterImpl;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.service.EvidenceService;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SimpleVectorStore;

import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Step 14 deterministic gate — M3.
 *
 * 100% offline, zero network, zero Postgres:
 * - In-process transport: PipedInputStream/PipedOutputStream wired between
 *   StdioServerTransportProvider (server) and a custom McpClientTransport (client).
 * - SimpleVectorStore + FakeEmbeddingModel for pgvector.
 * - Stub EvidenceService for diagnostics://cases/{runId}.
 *
 * Run:
 *   mvn test -pl mcp-server -Dtest=McpServerGateTest
 */
class McpServerGateTest {

    // -------------------------------------------------------------------------
    // In-process piped transport
    // -------------------------------------------------------------------------

    /**
     * Minimal McpClientTransport backed by piped streams — no subprocess, no HTTP.
     * The server writes to serverOut -> clientIn; client writes to clientOut -> serverIn.
     */
    private static class PipedClientTransport implements io.modelcontextprotocol.spec.McpClientTransport {

        private final PipedInputStream clientIn;
        private final PipedOutputStream clientOut;
        private final io.modelcontextprotocol.json.McpJsonMapper jsonMapper;

        private volatile boolean closing = false;
        private final reactor.core.publisher.Sinks.Many<McpSchema.JSONRPCMessage> inbound =
                reactor.core.publisher.Sinks.many().unicast().onBackpressureBuffer();

        PipedClientTransport(PipedInputStream clientIn, PipedOutputStream clientOut) {
            this.clientIn = clientIn;
            this.clientOut = clientOut;
            this.jsonMapper = McpJsonDefaults.getMapper();
        }

        @Override
        public reactor.core.publisher.Mono<Void> connect(
                java.util.function.Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>,
                        reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler) {
            // Start reader thread
            Thread.ofVirtual().start(() -> {
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(clientIn, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while (!closing && (line = reader.readLine()) != null) {
                        McpSchema.JSONRPCMessage msg = McpSchema.deserializeJsonRpcMessage(jsonMapper, line);
                        inbound.tryEmitNext(msg);
                    }
                } catch (Exception e) {
                    if (!closing) throw new RuntimeException(e);
                } finally {
                    inbound.tryEmitComplete();
                }
            });
            inbound.asFlux()
                    .flatMap(msg -> reactor.core.publisher.Mono.just(msg).transform(handler))
                    .subscribe();
            return reactor.core.publisher.Mono.empty();
        }

        @Override
        public reactor.core.publisher.Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
            return reactor.core.publisher.Mono.fromRunnable(() -> {
                try {
                    String json = jsonMapper.writeValueAsString(message)
                            .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "\\n");
                    synchronized (clientOut) {
                        clientOut.write((json + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        clientOut.flush();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }

        @Override
        public <T> T unmarshalFrom(Object data, io.modelcontextprotocol.json.TypeRef<T> typeRef) {
            return jsonMapper.convertValue(data, typeRef);
        }

        @Override
        public reactor.core.publisher.Mono<Void> closeGracefully() {
            closing = true;
            inbound.tryEmitComplete();
            return reactor.core.publisher.Mono.empty();
        }
    }

    // -------------------------------------------------------------------------
    // Stub EvidenceService
    // -------------------------------------------------------------------------

    private static class StubEvidenceService implements EvidenceService {
        private final Run run;
        private final List<Iteration> iterations;
        private final List<LoadReport> loadReports;

        StubEvidenceService(Run run, List<Iteration> iterations, List<LoadReport> loadReports) {
            this.run = run;
            this.iterations = iterations;
            this.loadReports = loadReports;
        }

        @Override
        public Run createRun(String targetId, String provider, String model,
                             String promptHash, String aggregatorVersion, Map<String, Object> genParams) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void transitionRunStatus(String runId,
                                        io.diag.evidence.RunStatus before, io.diag.evidence.RunStatus after) {}

        @Override
        public io.diag.evidence.entity.LoadReport createLoadReport(String runId, String label,
                                                                    LoadReportDto dto, Path k6SummaryFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.diag.evidence.entity.JfrReport createJfrReport(String runId, String label,
                                                                   io.diag.evidence.dto.JfrReportDto dto, Path jfrFile) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iteration createIteration(String runId, int n, HypothesisDto hypothesis,
                                         Map<String, Object> ledger, ChangeDto change, String outcome,
                                         String treeSha, Long loadReportId, Long jfrReportId,
                                         List<io.diag.evidence.dto.FilesTouchedDto> filesTouched,
                                         String keepType, String finding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public io.diag.evidence.entity.TrajectoryEvent createTrajectoryEvent(String runId, String kind,
                                                                               Map<String, Object> payload,
                                                                               Long tokensIn, Long tokensOut,
                                                                               BigDecimal costUsd) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void recordBaseline(String runId, double p95Ms, double p95FloorMs,
                                   double rps, double rpsFloor, String originSha) {}

        @Override
        public void recordKeptSha(String runId, String sha) {}

        @Override
        public Optional<Run> findRun(String runId) {
            return run != null && run.getId().equals(runId) ? Optional.of(run) : Optional.empty();
        }

        @Override
        public List<Iteration> findIterations(String runId) {
            return iterations;
        }

        @Override
        public List<LoadReport> findLoadReports(String runId) {
            return loadReports;
        }
    }

    // -------------------------------------------------------------------------
    // Test setup
    // -------------------------------------------------------------------------

    private McpSyncServer server;
    private McpSyncClient client;
    private PipedInputStream clientIn;
    private PipedOutputStream clientOut;

    @BeforeEach
    void setUp() throws Exception {
        // Piped streams: server reads from serverIn, writes to serverOut
        //                client reads from clientIn, writes to clientOut
        PipedOutputStream serverOut = new PipedOutputStream();
        PipedInputStream serverIn = new PipedInputStream();
        clientIn = new PipedInputStream(serverOut);   // client reads what server writes
        clientOut = new PipedOutputStream(serverIn);  // server reads what client writes

        // --- Domain objects ---
        // defaults() = (enabled, evaluationMode=false, text-embedding-3-small, 0.70, topK=3, schema 1).
        // The constructor rejects similarityThreshold <= 0.0, so an ad-hoc "0.0" instance would
        // throw in setUp(); the deterministic GateEmbeddingModel yields cosine 1.0 for the
        // seeded episode, which clears the 0.70 threshold.
        MemoryConfig memoryConfig = MemoryConfig.defaults();
        SimpleVectorStore vectorStore = SimpleVectorStore.builder(new GateEmbeddingModel()).build();

        // Seed one KEPT episode so happy-path tool call returns content
        vectorStore.add(List.of(new Document(
                "Lock contention on UrlJarFiles classloader under high concurrency",
                Map.of("targetId", "petclinic-s1", "outcome", "KEPT", "category", "H5",
                        "template", "jar-unpack", "keepType", "RPS",
                        "embeddingModel", "text-embedding-3-small", "schemaVersion", 1))));

        DiagnosticMemoryRetriever retriever = new DiagnosticMemoryRetrieverImpl(memoryConfig, vectorStore);
        MemoryContextFormatter formatter = new MemoryContextFormatterImpl();

        // Stub run for cases resource
        Run stubRun = Run.builder()
                .id("run-gate-001").targetId("petclinic-s1").status("COMPLETED")
                .model("claude-3-7-sonnet").baselineP95Ms(2500.0).baselineRps(190.0)
                .noiseFloorMs(125.0).noiseFloorRps(9.5).lastKeptSha("abc123")
                .startedAt(Instant.now()).build();
        stubRun.setNew(false);

        Iteration stubIter = Iteration.builder()
                .n(1).outcome("KEPT").keepType("RPS").finding("Unpacked jar eliminated classloader lock.")
                .hypothesis(new HypothesisDto("H5", 0.90, "classloader lock"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .ledger(Map.of("deltaRps", 47.0, "deltaP95Ms", -87.0))
                .build();

        LoadReportDto lrDto = new LoadReportDto("petclinic", "2025-01-01",
                280.0, 50000, new LatencyDto(80.0, 45.0, 210.0, 380.0, 950.0), 0.0, 1.0, null);
        LoadReport stubLr = LoadReport.builder().id(1L).runId("run-gate-001")
                .label("iter-1").payload(lrDto).build();

        EvidenceService evidenceService = new StubEvidenceService(stubRun, List.of(stubIter), List.of(stubLr));
        ObjectMapper objectMapper = new ObjectMapper();

        // --- Wire MCP service beans ---
        DiagnosticMemoryMcpService toolService = new DiagnosticMemoryMcpService(retriever, formatter);
        DiagnosticMcpResourceProvider resourceProvider = new DiagnosticMcpResourceProvider(evidenceService, objectMapper);

        // --- Build server with piped stdio transport ---
        StdioServerTransportProvider transport = new StdioServerTransportProvider(
                McpJsonDefaults.getMapper(), serverIn, serverOut);

        server = McpServer.sync(transport)
                .serverInfo("diag-memory-mcp-server", "1.0.0")
                .tools(toolService.mcpTools())
                .resources(resourceProvider.mcpResources())
                .immediateExecution(true)
                .build();

        // --- Build client ---
        PipedClientTransport clientTransport = new PipedClientTransport(clientIn, clientOut);
        client = McpClient.sync(clientTransport)
                .requestTimeout(java.time.Duration.ofSeconds(10))
                .build();
        client.initialize();
    }

    @AfterEach
    void tearDown() {
        if (client != null) client.closeGracefully();
        if (server != null) server.closeGracefully();
    }

    // -------------------------------------------------------------------------
    // 1. tools/list — query_past_findings is discoverable with correct schema
    // -------------------------------------------------------------------------

    @Test
    void gate1_toolsList_queryPastFindingsIsDiscoverable() {
        McpSchema.ListToolsResult result = client.listTools();

        assertThat(result.tools()).hasSize(1);
        McpSchema.Tool tool = result.tools().get(0);
        assertThat(tool.name()).isEqualTo("query_past_findings");
        assertThat(tool.description()).contains("episodic memory");
        // Schema must declare the 5 expected properties
        assertThat(tool.inputSchema().toString())
                .contains("symptomQuery")
                .contains("category")
                .contains("template")
                .contains("outcome")
                .contains("targetId");
    }

    // -------------------------------------------------------------------------
    // 2. tools/call — 6 guard edge cases
    // -------------------------------------------------------------------------

    @Test
    void gate2a_toolCall_allNullParams_returnsError() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings", Map.of()));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("At least one search parameter");
    }

    @Test
    void gate2b_toolCall_symptomQueryExceeds400Chars_returnsError() {
        String longQuery = "x".repeat(401);
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings", Map.of("symptomQuery", longQuery)));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("400");
    }

    @Test
    void gate2c_toolCall_invalidCategory_returnsError() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings", Map.of("category", "H9")));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("H1");
    }

    @Test
    void gate2d_toolCall_invalidOutcome_returnsError() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings", Map.of("outcome", "MAYBE")));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("KEPT");
    }

    @Test
    void gate2e_toolCall_templateExceeds50Chars_returnsError() {
        String longTemplate = "t".repeat(51);
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings", Map.of("template", longTemplate)));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("50");
    }

    @Test
    void gate2f_toolCall_happyPath_returnsAdvisoryMatrix() {
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings",
                        Map.of("symptomQuery", "classloader lock contention on jar")));
        assertThat(result.isError()).isFalse();
        String text = textOf(result);
        // Should contain the 2x2 advisory matrix tag for the seeded KEPT episode
        assertThat(text).containsAnyOf("PROVEN PATTERN", "BASELINE", "CAUTION", "OBSERVATION");
    }

    @Test
    void gate2g_toolCall_targetIdOnly_returnsError() {
        // targetId is provenance context, not a search criterion (spec Part 2) —
        // alone it must not trigger an unfiltered top-K dump
        McpSchema.CallToolResult result = client.callTool(
                new McpSchema.CallToolRequest("query_past_findings", Map.of("targetId", "petclinic-s1")));
        assertThat(result.isError()).isTrue();
        assertThat(textOf(result)).contains("targetId alone is not a search");
    }

    // -------------------------------------------------------------------------
    // 3. resources/read
    // -------------------------------------------------------------------------

    @Test
    void gate3a_resourceRead_hypothesesGuide_returnsMarkdown() {
        McpSchema.ReadResourceResult result = client.readResource(
                new McpSchema.ReadResourceRequest("diagnostics://hypotheses/guide"));
        assertThat(result.contents()).hasSize(1);
        String text = ((McpSchema.TextResourceContents) result.contents().get(0)).text();
        assertThat(text)
                .contains("H1").contains("H2").contains("H3")
                .contains("H4").contains("H5").contains("H6").contains("H7")
                .contains("JavaMonitorEnter");
    }

    @Test
    void gate3b_resourceRead_knownRunId_returnsForensicJson() {
        McpSchema.ReadResourceResult result = client.readResource(
                new McpSchema.ReadResourceRequest("diagnostics://cases/run-gate-001"));
        String json = ((McpSchema.TextResourceContents) result.contents().get(0)).text();
        assertThat(json)
                .contains("run-gate-001")
                .contains("petclinic-s1")
                .contains("COMPLETED")
                .contains("baselineP95Ms")
                .contains("baselineRps")
                .contains("iterations")
                .contains("jar-unpack")
                .contains("loadReports");
    }

    @Test
    void gate3c_resourceRead_unknownRunId_returnsErrorJson() {
        McpSchema.ReadResourceResult result = client.readResource(
                new McpSchema.ReadResourceRequest("diagnostics://cases/no-such-run"));
        String json = ((McpSchema.TextResourceContents) result.contents().get(0)).text();
        assertThat(json).contains("error");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String textOf(McpSchema.CallToolResult result) {
        return result.content().stream()
                .filter(c -> c instanceof McpSchema.TextContent)
                .map(c -> ((McpSchema.TextContent) c).text())
                .findFirst().orElse("");
    }

    // -------------------------------------------------------------------------
    // Minimal deterministic embedding model (no OpenAI dependency)
    // -------------------------------------------------------------------------

    private static class GateEmbeddingModel extends AbstractEmbeddingModel {
        @Override
        public EmbeddingResponse call(EmbeddingRequest request) {
            var embeddings = new java.util.ArrayList<Embedding>();
            for (int i = 0; i < request.getInstructions().size(); i++) {
                embeddings.add(new Embedding(vec(request.getInstructions().get(i)), i));
            }
            return new EmbeddingResponse(embeddings);
        }

        @Override
        public float[] embed(org.springframework.ai.document.Document document) {
            return vec(document != null ? document.getText() : "");
        }

        private static float[] vec(String text) {
            String t = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT);
            float[] v = new float[4];
            if (t.contains("lock") || t.contains("classloader") || t.contains("jar")) v[0] = 5f;
            if (t.contains("pool") || t.contains("hikari"))                           v[1] = 5f;
            if (t.contains("gc") || t.contains("heap"))                               v[2] = 5f;
            if (t.contains("cpu") || t.contains("hotspot"))                           v[3] = 5f;
            v[3] += 0.2f; // baseline so empty query has non-zero norm
            double norm = 0;
            for (float x : v) norm += (double) x * x;
            norm = Math.sqrt(norm);
            if (norm > 0) for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
            return v;
        }
    }
}
