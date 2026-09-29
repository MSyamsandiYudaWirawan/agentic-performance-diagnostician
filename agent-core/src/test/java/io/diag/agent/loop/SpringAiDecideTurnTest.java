package io.diag.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.decision.DecisionValidator;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.ThresholdsDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.service.EvidenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SpringAiDecideTurnTest {

    @TempDir
    Path tempDir;

    // Valid decision JSON matching the schema
    private static final String VALID_JSON = """
            {
              "hypothesis": {"category": "H5", "confidence": 0.8, "rationale": "lock contention"},
              "prediction": {"metricToImprove": "rps", "direction": "improve", "mechanismSignalToEliminate": "JavaMonitorEnter"},
              "ledger": {"category": "H5", "direction": "strengthen", "reason": "72k events"},
              "change": {"kind": "template", "template": "jar-unpack", "params": {}}
            }
            """;

    private static final String INVALID_JSON = "{ not valid json at all }";

    // -------------------------------------------------------------------------

    static class RecordingEvidenceService implements EvidenceService {
        record Event(String kind) {}
        final List<Event> events = new ArrayList<>();
        final List<Long> tokensInLog = new ArrayList<>();
        final List<BigDecimal> costLog = new ArrayList<>();

        @Override
        public TrajectoryEvent createTrajectoryEvent(String runId, String kind,
                                                     Map<String, Object> payload,
                                                     Long tokensIn, Long tokensOut,
                                                     BigDecimal costUsd) {
            events.add(new Event(kind));
            if (tokensIn != null) tokensInLog.add(tokensIn);
            if (costUsd != null) costLog.add(costUsd);
            return null;
        }

        @Override public Run createRun(String t, String p, String m, String h, String a, Map<String,Object> g) { return null; }
        @Override public void transitionRunStatus(String r, RunStatus b, RunStatus a) {}
        @Override public LoadReport createLoadReport(String r, String l, LoadReportDto d, Path f) { return null; }
        @Override public JfrReport createJfrReport(String r, String l, JfrReportDto d, Path f) { return null; }
        @Override public Iteration createIteration(String r, int n, HypothesisDto h, Map<String,Object> le,
                                                   ChangeDto c, String o, String t, Long lr, Long jr,
                                                   List<FilesTouchedDto> ft, String kt, String fi) { return null; }
        @Override public void recordBaseline(String r, double p95, double p95f, double rps, double rpsf, String sha) {}
        @Override public void recordKeptSha(String r, String sha) {}
    }

    private FakeChatPort chatPort;
    private RecordingEvidenceService svc;
    private SpringAiDecideTurn turn;
    private DecideContext ctx;

    @BeforeEach
    void setUp() {
        chatPort = new FakeChatPort();
        svc = new RecordingEvidenceService();
        ObjectMapper mapper = new ObjectMapper();
        DiagnosticTools tools = new DiagnosticTools(null, null, null, null, tempDir);
        DecisionValidator validator = new DecisionValidator(mapper,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        LoopConfig config = LoopConfig.defaults();

        // No-op sleeper — tests run at zero delay
        SpringAiDecideTurn.Sleeper noSleep = ms -> {};

        turn = new SpringAiDecideTurn(chatPort, validator, tools, config, mapper, svc, "run-1", noSleep);
        ctx = buildContext();
    }

    @Test
    void validDecision_firstAttempt_returnsDecision() {
        chatPort.enqueue(VALID_JSON, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.infraFailure()).isFalse();
        assertThat(result.error()).isNull();
        assertThat(result.tokensIn()).isEqualTo(100);
        assertThat(result.tokensOut()).isEqualTo(50);
    }

    @Test
    void fencedJson_strippedAndParsed() {
        String fenced = "```json\n" + VALID_JSON + "\n```";
        chatPort.enqueue(fenced, 80, 40);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.infraFailure()).isFalse();
    }

    @Test
    void proseBeforeAndAfterFencedJson_extractedAndParsed() {
        String output = "The analysis of the baseline shows lock contention on UrlJarFiles$Cache.\n"
                + "```json\n" + VALID_JSON + "\n```\n"
                + "This change is predicted to eliminate the lock.";
        chatPort.enqueue(output, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.decision().hypothesis().category()).isEqualTo("H5");
        assertThat(result.infraFailure()).isFalse();
    }

    @Test
    void proseBeforeUnfencedJson_extractedAndParsed() {
        String output = "Here is my final decision:\n" + VALID_JSON;
        chatPort.enqueue(output, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.decision().hypothesis().category()).isEqualTo("H5");
    }

    @Test
    void invalidJson_retriedOnce_thenWasted() {
        chatPort.enqueue(INVALID_JSON, 50, 20);
        chatPort.enqueue(INVALID_JSON, 50, 20);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNull();
        assertThat(result.infraFailure()).isFalse();
        assertThat(result.error()).contains("rejected after retry");
        assertThat(chatPort.callCount()).isEqualTo(2);
    }

    @Test
    void invalidJson_firstAttempt_validOnRetry_returnsDecision() {
        chatPort.enqueue(INVALID_JSON, 50, 20);
        chatPort.enqueue(VALID_JSON, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.infraFailure()).isFalse();
        assertThat(result.tokensIn()).isEqualTo(150);
        assertThat(result.tokensOut()).isEqualTo(70);
    }

    @Test
    void infraException_allAttemptsExhausted_returnsInfraFailure() {
        RuntimeException ex = new RuntimeException("connection refused");
        chatPort.enqueueThrow(ex).enqueueThrow(ex).enqueueThrow(ex);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNull();
        assertThat(result.infraFailure()).isTrue();
        assertThat(result.error()).contains("Infra failure");
        assertThat(chatPort.callCount()).isEqualTo(3);
    }

    @Test
    void infraException_firstAttempt_succeedsOnSecond_returnsDecision() {
        chatPort.enqueueThrow(new RuntimeException("timeout"))
                .enqueue(VALID_JSON, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.infraFailure()).isFalse();
        assertThat(chatPort.callCount()).isEqualTo(2);
    }

    @Test
    void trajectoryEvents_llmReqAndLlmRespLogged() {
        chatPort.enqueue(VALID_JSON, 100, 50);

        turn.decide(1, ctx);

        List<String> kinds = svc.events.stream().map(RecordingEvidenceService.Event::kind).toList();
        assertThat(kinds).contains("LLM_REQ", "LLM_RESP");
    }

    @Test
    void costCalculation_withConfiguredPrices_computesAccurateCost() {
        LoopConfig pricedConfig = new LoopConfig(
                5, 3_600_000L, 500_000L, new BigDecimal("10.00"), 10, 0.50,
                new BigDecimal("3.00"),   // $3 per Mtok in
                new BigDecimal("15.00")   // $15 per Mtok out
        );
        ObjectMapper mapper = new ObjectMapper();
        DiagnosticTools tools = new DiagnosticTools(null, null, null, null, tempDir);
        DecisionValidator validator = new DecisionValidator(mapper,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        SpringAiDecideTurn pricedTurn = new SpringAiDecideTurn(
                chatPort, validator, tools, pricedConfig, mapper, svc, "run-1", ms -> {});

        // 1,000,000 in ($3.00) + 1,000,000 out ($15.00) = $18.00
        chatPort.enqueue(VALID_JSON, 1_000_000, 1_000_000);

        pricedTurn.decide(1, ctx);

        assertThat(svc.costLog).anyMatch(c -> c.compareTo(new BigDecimal("18.00")) == 0);
    }

    @Test
    void decide_nullContext_throwsNullPointerException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> turn.decide(1, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ctx must not be null");
    }

    @Test
    void infraException_interruptedDuringBackoff_returnsInfraFailure() {
        chatPort.enqueueThrow(new RuntimeException("timeout 1"))
                .enqueueThrow(new RuntimeException("timeout 2"));

        SpringAiDecideTurn.Sleeper interruptingSleeper = ms -> {
            throw new InterruptedException("simulated interrupt");
        };

        ObjectMapper mapper = new ObjectMapper();
        DiagnosticTools tools = new DiagnosticTools(null, null, null, null, tempDir);
        DecisionValidator validator = new DecisionValidator(mapper,
                jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
        SpringAiDecideTurn turnWithInterrupt = new SpringAiDecideTurn(
                chatPort, validator, tools, LoopConfig.defaults(), mapper, svc, "run-1", interruptingSleeper);

        DecideTurn.DecideResult result = turnWithInterrupt.decide(1, ctx);

        assertThat(result.decision()).isNull();
        assertThat(result.infraFailure()).isTrue();
        assertThat(result.error()).contains("Interrupted during backoff");
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        // Clear interrupt flag so other tests aren't affected
        Thread.interrupted();
    }

    @Test
    void promptIncludesRepoStructureAndInstructions() throws Exception {
        java.nio.file.Files.writeString(tempDir.resolve("pom.xml"), "<project/>");
        java.nio.file.Files.writeString(tempDir.resolve("Dockerfile.target"), "FROM eclipse-temurin:21");

        chatPort.enqueue(VALID_JSON, 100, 50);

        turn.decide(1, ctx);

        String prompt = chatPort.lastUserPrompt();
        assertThat(prompt).contains("REPOSITORY STRUCTURE:");
        assertThat(prompt).contains("- pom.xml");
        assertThat(prompt).contains("- Dockerfile.target");
        assertThat(prompt).contains("INVESTIGATION & OUTPUT INSTRUCTIONS:");
    }

    @Test
    void retryAttempt_hasNoToolsAndIncludesSchema() {
        chatPort.enqueue(INVALID_JSON, 50, 20);
        chatPort.enqueue(VALID_JSON, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        // On retry, tools must be empty (List.of()) so model cannot invoke another tool cycle
        assertThat(chatPort.lastToolBeans()).isEmpty();
        assertThat(chatPort.lastUserPrompt()).contains("[CRITICAL ERROR:");
        assertThat(chatPort.lastUserPrompt()).contains("OUTPUT THE RAW JSON OBJECT NOW:");
    }

    @Test
    void proseWithCodeBracesBeforeJson_extractedAndParsed() {
        String output = "Here is what I checked in code: `public void test() { System.out.println(1); }`\n"
                + "Final decision:\n" + VALID_JSON;
        chatPort.enqueue(output, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.decision().hypothesis().category()).isEqualTo("H5");
    }

    @Test
    void structuredProseWithoutJson_extractedAndValidated() {
        String prose = """
                The Dockerfile.target confirms the app runs as java -jar /app/app.jar.
                JFR shows JavaMonitorEnter CRITICAL on UrlJarFiles$Cache.
                The fix template is jar-unpack.
                Hypothesis: H5 lock contention. Confidence: 0.85.
                Prediction: eliminate JavaMonitorEnter, improve p95.
                Ledger: strengthen H5.
                Change: template jar-unpack with params {}.
                Output only JSON.
                """;
        chatPort.enqueue(prose, 100, 50);

        DecideTurn.DecideResult result = turn.decide(1, ctx);

        assertThat(result.decision()).isNotNull();
        assertThat(result.decision().hypothesis().category()).isEqualTo("H5");
        assertThat(result.decision().hypothesis().confidence()).isEqualTo(0.85);
        assertThat(result.decision().prediction().metricToImprove()).isEqualTo("p95");
        assertThat(result.decision().prediction().mechanismSignalToEliminate()).isEqualTo("JavaMonitorEnter");
        assertThat(result.decision().change().template()).isEqualTo("jar-unpack");
        assertThat(result.infraFailure()).isFalse();
    }

    // -------------------------------------------------------------------------

    private DecideContext buildContext() {
        LoadReportDto ref = new LoadReportDto(
                "spring-petclinic", "2026-09-25T10:00:00Z",
                189.0, 10_000,
                new LatencyDto(2200, 1058, 2500, 4000, 10000),
                0.0, 1.0,
                new ThresholdsDto("FAIL", List.of()));
        NoiseFloors floors = new NoiseFloors(125.0, 9.45, 5.0);
        return new DecideContext("spring-petclinic", 1, 5, ref, floors,
                null, Map.of("H5", 0.0), List.of());
    }
}
