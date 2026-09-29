package io.diag.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.config.AgentConfig;
import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionValidator;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.DockerTargetPipeline;
import io.diag.agent.loop.LoopConfig;
import io.diag.agent.loop.SpringAiChatPort;
import io.diag.agent.loop.SpringAiDecideTurn;
import io.diag.agent.loop.StartupJanitor;
import io.diag.agent.loop.SystemPrompts;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.AgentGateApp;
import io.diag.evidence.RunStatus;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.service.EvidenceService;
import io.diag.runner.config.Thresholds;
import io.diag.runner.service.BenchmarkRunner;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.CodeTouchGate;
import io.diag.runner.service.DockerEnvelopeGuard;
import io.diag.runner.service.FixTemplateRegistry;
import io.diag.runner.service.JfrAnalyzer;
import io.diag.runner.service.JfrCapture;
import io.diag.runner.service.TargetBuilder;
import io.diag.runner.service.TargetStack;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Step 9 Milestone 4 (M4) verify gate (spec Part 10 M4, build-steps step 9).
 *
 * Full real end-to-end autonomous diagnostic run:
 * - Real target repository: targets/spring-petclinic
 * - Real Docker stack: TargetStack (h2 + service + k6 with 2 CPU / 2 GB budget)
 * - Real profiling: JDK JFR dumponexit harvested into evidence/artifacts
 * - Real database: always-on diag-evidence Postgres on localhost:5432
 * - Real LLM: Z.AI glm-5.3 via Anthropic-protocol endpoint
 *
 * Opt-in flag:
 *   mvn -pl agent-core test "-Dstep9.real=true" "-Dtest=Step9RealRunGate"
 *   (or "-Dstep9.gate=true")
 *
 * Class name MUST match file name (Step8 lesson: class/file mismatch = false green).
 */
public class Step9RealRunGate {

    private static final Logger log = LoggerFactory.getLogger(Step9RealRunGate.class);

    private static final Path PROJECT_ROOT  = Path.of("C:/study/agentic-performance-diagnostician");
    private static final Path TARGET_REPO   = PROJECT_ROOT.resolve("targets/spring-petclinic");
    private static final Path EVIDENCE_ROOT = PROJECT_ROOT.resolve("evidence/artifacts");

    @Test
    void realEndToEndS1Run() throws Exception {
        boolean enabled = Boolean.getBoolean("step9.real") || Boolean.getBoolean("step9.gate");
        if (!enabled) {
            log.info("[Step9RealRunGate] skipping real S1 run: neither -Dstep9.real=true nor -Dstep9.gate=true set");
            return;
        }

        String apiKey = System.getenv("ZAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            Path envFile = PROJECT_ROOT.resolve(".env");
            if (Files.exists(envFile)) {
                for (String line : Files.readAllLines(envFile)) {
                    String trimmed = line.trim();
                    if (trimmed.startsWith("ZAI_API_KEY=")) {
                        apiKey = trimmed.substring("ZAI_API_KEY=".length()).trim();
                        System.setProperty("spring.ai.anthropic.api-key", apiKey);
                        break;
                    }
                }
            }
        }
        assumeTrue(apiKey != null && !apiKey.isBlank(),
                "ZAI_API_KEY not set in env or .env — the M4 real end-to-end run requires a valid API key");

        // Verify target repository exists and is clean before starting
        assertTrue(Files.exists(TARGET_REPO), "Target repo targets/spring-petclinic must exist");
        ChangeApplier changeApplier = new ChangeApplier(new FixTemplateRegistry());
        String originSha = changeApplier.currentSha(TARGET_REPO);
        Objects.requireNonNull(originSha, "originSha must not be null");

        String statusOutput = gitOutput(TARGET_REPO, "status", "--porcelain");
        assertThat(statusOutput.trim()).as("Target repo must be clean before starting M4 run").isEmpty();

        // Boot the Spring context with real Postgres and real LLM client
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(AgentGateApp.class, AgentConfig.class)
                .properties(
                        "spring.datasource.url=jdbc:postgresql://localhost:5432/diagnostician?stringtype=unspecified",
                        "spring.datasource.username=diag",
                        "spring.datasource.password=diag",
                        "evidence.artifacts-root=" + EVIDENCE_ROOT.toString().replace('\\', '/'),
                        "spring.ai.retry.max-attempts=1",
                        "spring.ai.anthropic.api-key=" + apiKey)
                .run();
             ValidatorFactory validatorFactory = Validation.buildDefaultValidatorFactory()) {

            EvidenceService evidenceService = ctx.getBean(EvidenceService.class);
            RunRepository runRepository     = ctx.getBean(RunRepository.class);
            ObjectMapper mapper             = ctx.getBean(ObjectMapper.class);
            ChatClient chatClient           = ctx.getBean(ChatClient.class);
            GenParams genParams             = ctx.getBean(GenParams.class);

            // Step 1: Run StartupJanitor to clean orphaned compose projects from prior crashed runs
            StartupJanitor janitor = new StartupJanitor(mapper, runRepository);
            janitor.cleanup(null);

            // Step 2: Ensure single-flight lock holds (no existing RUNNING row)
            evidenceService.findRunningRun().ifPresent(running -> {
                throw new IllegalStateException("A run is already RUNNING in Evidence DB: " + running.getId());
            });

            // Step 3: Create the Run row in Evidence DB (mint runId first)
            Run run = evidenceService.createRun(
                    "S1",
                    genParams.provider(),
                    genParams.model(),
                    SystemPrompts.promptHash(),
                    "1.0",
                    genParams.toMap());
            String runId = run.getId();
            log.info("[Step9RealRunGate] minted run: {} for target S1", runId);

            // Step 4: Construct the run-scoped runner chain (mirrors Step 8 gate pattern)
            TargetBuilder targetBuilder     = new TargetBuilder();
            CodeTouchGate codeTouchGate     = new CodeTouchGate();
            JfrAnalyzer jfrAnalyzer         = new JfrAnalyzer(Thresholds.fromEnv());
            TargetStack targetStack         = new TargetStack(PROJECT_ROOT, TARGET_REPO, runId, EVIDENCE_ROOT);
            DockerEnvelopeGuard guard       = new DockerEnvelopeGuard();
            BenchmarkRunner benchmarkRunner = new BenchmarkRunner(
                    targetStack, evidenceService, guard, mapper, EVIDENCE_ROOT, runId, "spring-petclinic");

            DockerTargetPipeline targetPipeline = new DockerTargetPipeline(
                    targetStack, benchmarkRunner, targetBuilder, codeTouchGate,
                    jfrAnalyzer, evidenceService, TARGET_REPO, EVIDENCE_ROOT, runId);

            SpringAiChatPort chatPort = new SpringAiChatPort(chatClient);
            DecisionValidator validator = new DecisionValidator(mapper, validatorFactory.getValidator());
            DiagnosticTools diagnosticTools = new DiagnosticTools(
                    benchmarkRunner,
                    new JfrCapture(targetStack, evidenceService, runId),
                    jfrAnalyzer,
                    changeApplier,
                    TARGET_REPO);

            LoopConfig loopConfig = LoopConfig.fromEnv();
            SpringAiDecideTurn decideTurn = new SpringAiDecideTurn(
                    chatPort, validator, diagnosticTools, loopConfig, mapper, evidenceService, runId);

            AgentLoop agentLoop = new AgentLoop(
                    evidenceService, changeApplier, decideTurn, targetPipeline,
                    loopConfig, genParams, TARGET_REPO, "S1");

            // Step 5: Execute the loop and monitor progress
            try {
                log.info("[Step9RealRunGate] starting AgentLoop for run {}...", runId);
                String activeId = agentLoop.start(runId);
                assertThat(activeId).isEqualTo(runId);
                log.info("[Step9RealRunGate] AgentLoop completed for run {}", runId);
            } finally {
                // Guaranteed resource cleanup
                targetPipeline.close();
                // Single-flight guarantee: if still RUNNING, transition to INCOMPLETE
                try {
                    evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, RunStatus.INCOMPLETE);
                } catch (IllegalStateException alreadyTransitioned) {
                    // Normal — run reached COMPLETED or ABORTED
                }
            }

            // Step 6: Post-run DB assertions
            Run finalRun = evidenceService.findRun(runId)
                    .orElseThrow(() -> new AssertionError("Run row missing for " + runId));

            log.info("[Step9RealRunGate] final run status: {}, originSha: {}, lastKeptSha: {}, baselineRps: {}, baselineP95: {}",
                    finalRun.getStatus(), finalRun.getOriginSha(), finalRun.getLastKeptSha(),
                    finalRun.getBaselineRps(), finalRun.getBaselineP95Ms());

            assertThat(finalRun.getStatus()).isIn(
                    RunStatus.COMPLETED.name(),
                    RunStatus.ABORTED.name(),
                    RunStatus.INCOMPLETE.name());

            assertThat(finalRun.getOriginSha()).isNotNull().isEqualTo(originSha);
            assertThat(finalRun.getLastKeptSha()).isNotNull();
            assertThat(finalRun.getBaselineRps()).isNotNull();
            assertThat(finalRun.getBaselineP95Ms()).isNotNull();
            assertThat(finalRun.getNoiseFloorRps()).isNotNull();
            assertThat(finalRun.getNoiseFloorMs()).isNotNull();

            // Step 7: Check iterations recorded
            List<Iteration> iterations = evidenceService.findIterations(runId);
            log.info("[Step9RealRunGate] iterations recorded: {}", iterations.size());
            for (Iteration iter : iterations) {
                log.info("  Iter {}: outcome={}, keepType={}, treeSha={}, finding={}",
                        iter.getN(), iter.getOutcome(), iter.getKeepType(), iter.getTreeSha(), iter.getFinding());
                assertThat(iter.getOutcome()).isNotNull();
                assertThat(iter.getTreeSha()).isNotNull();
            }

            // Step 8: Check on-disk artifacts for baselines
            Path runArtifactsDir = EVIDENCE_ROOT.resolve(runId);
            for (int k = 1; k <= 3; k++) {
                Path baseDir = runArtifactsDir.resolve("baseline-" + k);
                assertTrue(Files.exists(baseDir.resolve("k6-summary.json")),
                        "k6 summary must exist for baseline-" + k);
                assertTrue(Files.exists(baseDir.resolve("profile.jfr")),
                        "profile.jfr must exist for baseline-" + k);
            }

            // Step 9: Verify target repo HEAD matches lastKeptSha and working tree is clean
            String currentSha = changeApplier.currentSha(TARGET_REPO);
            assertThat(currentSha).isEqualTo(finalRun.getLastKeptSha());
            String endStatus = gitOutput(TARGET_REPO, "status", "--porcelain");
            assertThat(endStatus.trim()).isEmpty();
        }
    }

    private static String gitOutput(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        String output = new String(p.getInputStream().readAllBytes());
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
        return output;
    }
}
