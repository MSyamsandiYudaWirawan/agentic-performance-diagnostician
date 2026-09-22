package io.diag.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.decision.DecisionValidator;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.AgentGateApp;
import io.diag.evidence.RunStatus;
import io.diag.evidence.service.EvidenceService;
import io.diag.runner.config.Thresholds;
import io.diag.runner.service.BenchmarkRunner;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.DockerEnvelopeGuard;
import io.diag.runner.service.FixTemplateRegistry;
import io.diag.runner.service.JfrAnalyzer;
import io.diag.runner.service.JfrCapture;
import io.diag.runner.service.TargetBuilder;
import io.diag.runner.service.TargetStack;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step 8 verify gate (build-steps step 8): tools callable by the model +
 * structured decisions validated before any apply.
 *
 * No-API tests (always run, plain JUnit — no Spring context, no DB, no Docker):
 * decision validation matrix + readSource confinement/cap.
 *
 * API-dependent test (opt-in): one real chat turn — the §10.8 provider
 * tool-calling reliability check. Boots the evidence context (always-on
 * Postgres), builds the run-scoped runner chain, brings the stack up, and
 * asks the model to call runBenchmark. Ground truth is the k6 summary
 * artifact, not the model's prose.
 *
 * Opt-in (needs ZAI_API_KEY + Docker + diag-evidence Postgres up):
 *   mvn -pl agent-core -am test "-Dstep8.gate=true" "-Dtest=Step8VerifyGate"
 *      "-Dsurefire.failIfNoSpecifiedTests=false"
 *
 * Class name MUST match the file name — a mismatch makes the documented
 * -Dtest selector silently run zero tests (false green; caught 2026-09-22).
 */
class Step8VerifyGate {

    static final Path PROJECT_ROOT  = Path.of("C:/study/agentic-performance-diagnostician");
    static final Path TARGET_REPO   = PROJECT_ROOT.resolve("targets/spring-petclinic");
    static final Path EVIDENCE_ROOT = PROJECT_ROOT.resolve("evidence/artifacts");
    static final Path BUILD_LOG     = PROJECT_ROOT.resolve("step8-gate-build.log");

    ValidatorFactory factory;
    DecisionValidator validator;

    @TempDir
    Path tempRoot;

    @BeforeEach
    void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = new DecisionValidator(new ObjectMapper(), factory.getValidator());
    }

    @AfterEach
    void tearDown() {
        factory.close();
    }

    // -------------------------------------------------------------------------
    // Decision validation matrix — always run, no API (§10.21)
    // -------------------------------------------------------------------------

    @Test
    void rejectsBadCategory() {
        String json = """
                {"hypothesis":{"category":"H9","confidence":0.8,"rationale":"x"},
                 "prediction":{"metricToImprove":"rps","direction":"improve","mechanismSignalToEliminate":"JavaMonitorEnter"},
                 "ledger":{"category":"H9","direction":"strengthen","reason":"x"},
                 "change":{"kind":"template","template":"jar-unpack"}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("H1–H7");
    }

    @Test
    void rejectsMissingPrediction() {
        String json = """
                {"hypothesis":{"category":"H5","confidence":0.8,"rationale":"x"},
                 "ledger":{"category":"H5","direction":"strengthen","reason":"x"},
                 "change":{"kind":"template","template":"jar-unpack"}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("prediction");
    }

    @Test
    void rejectsOutOfRangeConfidence() {
        String json = """
                {"hypothesis":{"category":"H5","confidence":1.5,"rationale":"x"},
                 "prediction":{"metricToImprove":"rps","direction":"improve","mechanismSignalToEliminate":"JavaMonitorEnter"},
                 "ledger":{"category":"H5","direction":"strengthen","reason":"x"},
                 "change":{"kind":"template","template":"jar-unpack"}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("confidence");
    }

    @Test
    void rejectsKindEditsWithEmptyEdits() {
        String json = """
                {"hypothesis":{"category":"H5","confidence":0.8,"rationale":"x"},
                 "prediction":{"metricToImprove":"rps","direction":"improve","mechanismSignalToEliminate":"JavaMonitorEnter"},
                 "ledger":{"category":"H5","direction":"strengthen","reason":"x"},
                 "change":{"kind":"edits","edits":[]}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("edits");
    }

    @Test
    void rejectsKindTemplateWithBlankId() {
        String json = """
                {"hypothesis":{"category":"H5","confidence":0.8,"rationale":"x"},
                 "prediction":{"metricToImprove":"rps","direction":"improve","mechanismSignalToEliminate":"JavaMonitorEnter"},
                 "ledger":{"category":"H5","direction":"strengthen","reason":"x"},
                 "change":{"kind":"template","template":""}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("template");
    }

    @Test
    void rejectsNullChangeKind() {
        // kind absent entirely — must die at decision validation (§10.21),
        // not later inside ChangeApplier
        String json = """
                {"hypothesis":{"category":"H5","confidence":0.8,"rationale":"x"},
                 "prediction":{"metricToImprove":"rps","direction":"improve","mechanismSignalToEliminate":"JavaMonitorEnter"},
                 "ledger":{"category":"H5","direction":"strengthen","reason":"x"},
                 "change":{"template":"jar-unpack"}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("change.kind");
    }

    @Test
    void rejectsUnparseableJson() {
        var result = validator.validate("this is not json");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).startsWith("Decision parse error");
    }

    @Test
    void acceptsValidDecision() {
        String json = """
                {"hypothesis":{"category":"H5","confidence":0.8,"rationale":"fat-jar classloader lock"},
                 "prediction":{"metricToImprove":"rps","direction":"improve","mechanismSignalToEliminate":"JavaMonitorEnter"},
                 "ledger":{"category":"H5","direction":"strengthen","reason":"72k monitor events"},
                 "change":{"kind":"template","template":"jar-unpack"}}
                """;
        var result = validator.validate(json);
        assertThat(result.ok()).isTrue();
        assertThat(result.data().hypothesis().category()).isEqualTo("H5");
    }

    // -------------------------------------------------------------------------
    // readSource confinement + cap — always run, no API (§10.2)
    // -------------------------------------------------------------------------

    private DiagnosticTools readTools() {
        // readSource only uses targetRoot; the other collaborators are null by
        // design in this unit slice (DiagnosticTools has no null-check ctor contract)
        return new DiagnosticTools(null, null, null, null, tempRoot);
    }

    @Test
    void readSourceReturnsFileContent() throws Exception {
        Files.writeString(tempRoot.resolve("Dockerfile.target"), "FROM eclipse-temurin\n");
        var result = readTools().readSource("Dockerfile.target");
        assertThat(result.ok()).isTrue();
        assertThat(result.data()).isEqualTo("FROM eclipse-temurin\n");
    }

    @Test
    void readSourceRejectsEscapeAndAbsolute() {
        var tools = readTools();
        assertThat(tools.readSource("../evil.txt").ok()).isFalse();
        assertThat(tools.readSource(tempRoot.getParent().resolve("evil.txt").toString()).ok()).isFalse();
    }

    @Test
    void readSourceRejectsOverCap() throws Exception {
        Files.write(tempRoot.resolve("big.txt"), new byte[100 * 1024 + 1]);
        var result = readTools().readSource("big.txt");
        assertThat(result.ok()).isFalse();
        assertThat(result.error()).contains("100 KB");
    }

    // -------------------------------------------------------------------------
    // API-dependent gate — §10.8 provider tool-calling reliability
    // Opt-in: -Dstep8.gate=true + SPRING_AI_OPENAI_API_KEY + Docker + Postgres
    // -------------------------------------------------------------------------

    @Test
    void modelCallsRunBenchmarkAndReturnsNumbers() throws Exception {
        if (!Boolean.getBoolean("step8.gate")) return;
        String apiKey = System.getenv("ZAI_API_KEY");
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ZAI_API_KEY not set — the §10.8 provider test needs a real key");

        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AgentGateApp.class)
                .properties(
                        // the always-on evidence Postgres (docker/evidence compose)
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/diagnostician?stringtype=unspecified",
                        "spring.datasource.username=diag",
                        "spring.datasource.password=diag",
                        "evidence.artifacts-root=" + EVIDENCE_ROOT)
                .run()) {

            EvidenceService evidenceService = ctx.getBean(EvidenceService.class);
            ObjectMapper mapper = ctx.getBean(ObjectMapper.class);
            // ChatClient from the autoconfigured builder — exercises the real
            // starter + application.properties options path (§10.8)
            ChatClient chatClient = ctx.getBean(ChatClient.Builder.class).build();

            String runId = evidenceService
                    .createRun("S1", "manual", "none", "step8-gate", "manual", Map.of())
                    .getId();
            try {
                Path jarRel = new TargetBuilder().build(TARGET_REPO, BUILD_LOG);
                Files.deleteIfExists(BUILD_LOG);

                try (TargetStack stack = new TargetStack(PROJECT_ROOT, TARGET_REPO, runId, EVIDENCE_ROOT)) {
                    // run-scoped chain, the step-9 shape (§10.19) — constructed here,
                    // not scanned: these objects belong to one run, not a context
                    DiagnosticTools tools = new DiagnosticTools(
                            new BenchmarkRunner(stack, evidenceService, new DockerEnvelopeGuard(),
                                    mapper, EVIDENCE_ROOT, runId, "spring-petclinic"),
                            new JfrCapture(stack, evidenceService, runId),
                            new JfrAnalyzer(Thresholds.fromEnv()),
                            new ChangeApplier(new FixTemplateRegistry()),
                            TARGET_REPO);

                    // tools assume the loop brought the stack up (§4.1) — the gate does it
                    stack.up("gate-1", jarRel);

                    String content = chatClient.prompt()
                            .user("""
                                    You are a JVM performance diagnostician. Call the runBenchmark
                                    tool with label "gate-1" to measure the target service, then report
                                    the measured rps and p95 in one sentence.
                                    """)
                            .tools(tools)
                            .call()
                            .content();

                    assertThat(content).isNotBlank();
                    // Ground truth: the tool really executed — k6 wrote its summary
                    // where BenchmarkRunner reads it. Artifact, not prose.
                    assertTrue(
                            Files.exists(EVIDENCE_ROOT.resolve(runId).resolve("gate-1").resolve("k6-summary.json")),
                            "runBenchmark must have actually run — k6 summary artifact missing");

                    stack.stopAndHarvest("gate-1");
                }

                evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.COMPLETED);
            } finally {
                // best-effort: never leave a RUNNING row holding the single-flight
                // lock (§10.27) — mirrors the step-4 gate's finally
                try {
                    evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.INCOMPLETE);
                } catch (IllegalStateException alreadyTransitioned) {
                    System.err.println("[step8-gate] run " + runId + " already transitioned: "
                            + alreadyTransitioned.getMessage());
                }
            }
        }
    }
}
