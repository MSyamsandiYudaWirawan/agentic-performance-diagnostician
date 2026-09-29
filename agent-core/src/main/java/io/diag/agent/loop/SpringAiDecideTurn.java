package io.diag.agent.loop;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.decision.DecisionValidator;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.service.EvidenceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Two-track retry policy (§5.7, D3):
 *   Track 1 — infrastructure fault (timeout/IO/429-5xx): up to 3 attempts,
 *             30 s then 60 s backoff; still failing → infraFailure=true, run INCOMPLETE.
 *   Track 2 — model fault (bad JSON): one retry with the validation error
 *             appended; still invalid → infraFailure=false, iteration WASTED.
 *
 * Sleeper is injected so tests run at 0 ms delay without Thread.sleep.
 */
@Component
public class SpringAiDecideTurn implements DecideTurn {

    private static final Logger log = LoggerFactory.getLogger(SpringAiDecideTurn.class);

    /** Pluggable sleep — real impl uses Thread::sleep; tests inject a no-op. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long ms) throws InterruptedException;
    }

    private static final int   INFRA_MAX_ATTEMPTS = 3;
    private static final long  BACKOFF_FIRST_MS   = 30_000L;
    private static final long  BACKOFF_SECOND_MS  = 60_000L;

    private final ChatPort          chatPort;
    private final DecisionValidator validator;
    private final DiagnosticTools   diagnosticTools;
    private final LoopConfig        loopConfig;
    private final ObjectMapper      mapper;
    private final EvidenceService   trajectory;
    private final String            runId;
    private final Sleeper           sleeper;

    /** Production constructor — uses Thread::sleep for backoff. */
    public SpringAiDecideTurn(ChatPort chatPort,
                              DecisionValidator validator,
                              DiagnosticTools diagnosticTools,
                              LoopConfig loopConfig,
                              ObjectMapper mapper,
                              EvidenceService trajectory,
                              String runId) {
        this(chatPort, validator, diagnosticTools, loopConfig, mapper,
             trajectory, runId, Thread::sleep);
    }

    /** Full constructor — Sleeper injected for testing. */
    public SpringAiDecideTurn(ChatPort chatPort,
                              DecisionValidator validator,
                              DiagnosticTools diagnosticTools,
                              LoopConfig loopConfig,
                              ObjectMapper mapper,
                              EvidenceService trajectory,
                              String runId,
                              Sleeper sleeper) {
        this.chatPort        = Objects.requireNonNull(chatPort,        "chatPort must not be null");
        this.validator       = Objects.requireNonNull(validator,       "validator must not be null");
        this.diagnosticTools = Objects.requireNonNull(diagnosticTools, "diagnosticTools must not be null");
        this.loopConfig      = Objects.requireNonNull(loopConfig,      "loopConfig must not be null");
        this.mapper          = Objects.requireNonNull(mapper,          "mapper must not be null");
        this.trajectory      = Objects.requireNonNull(trajectory,      "trajectory must not be null");
        this.runId           = Objects.requireNonNull(runId,           "runId must not be null");
        this.sleeper         = Objects.requireNonNull(sleeper,         "sleeper must not be null");
    }

    @Override
    public DecideResult decide(int n, DecideContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        String userContext = SystemPrompts.buildUserContext(ctx);
        BoundedReadSource reader = new BoundedReadSource(
                diagnosticTools, loopConfig.toolCallBound(), trajectory, runId);
        trajectory.createTrajectoryEvent(runId, "LLM_REQ",
                Map.of("iteration", n), 0L, 0L, BigDecimal.ZERO);
        return decideInternal(n, userContext, reader, ctx);
    }

    private DecideResult decideInternal(int n, String userContext,
                                        BoundedReadSource reader, DecideContext ctx) {
        long totalTokensIn  = 0L;
        long totalTokensOut = 0L;

        // --- Track 1: infrastructure retry for first call ---
        InfraResult first = callWithInfraRetry(userContext, List.of(reader));
        totalTokensIn  += first.tokensIn;
        totalTokensOut += first.tokensOut;

        if (!first.ok) {
            logLlmResp(totalTokensIn, totalTokensOut, null);
            return new DecideResult(null, first.error, true, totalTokensIn, totalTokensOut, null);
        }

        String rawText = first.text;
        logLlmResp(totalTokensIn, totalTokensOut, rawText);

        String stripped = stripFences(rawText);
        var envelope = validator.validate(stripped);

        if (envelope.ok()) {
            return new DecideResult(envelope.data(), null, false,
                    totalTokensIn, totalTokensOut, rawText);
        }

        log.warn("Attempt 1 decision validation failed at iteration {}: {}. Raw text:\n{}",
                n, envelope.error(), rawText);

        // --- Track 2: one model retry with the validation error appended ---
        String retryPrompt = userContext
                + "\n\n[CRITICAL: Your previous decision was rejected with error: " + envelope.error()
                + ".\nYou must output ONLY the raw JSON object starting with '{' and ending with '}'. "
                + "Do NOT write any explanation, markdown prose, or conversational text before or after the JSON.]";

        InfraResult second = callWithInfraRetry(retryPrompt, List.of(reader));
        totalTokensIn  += second.tokensIn;
        totalTokensOut += second.tokensOut;

        if (!second.ok) {
            logLlmResp(totalTokensIn, totalTokensOut, null);
            return new DecideResult(null, second.error, true, totalTokensIn, totalTokensOut, null);
        }

        String rawText2  = second.text;
        logLlmResp(totalTokensIn, totalTokensOut, rawText2);

        String stripped2 = stripFences(rawText2);
        var envelope2    = validator.validate(stripped2);

        if (envelope2.ok()) {
            return new DecideResult(envelope2.data(), null, false,
                    totalTokensIn, totalTokensOut, rawText2);
        }

        log.warn("Attempt 2 decision validation failed at iteration {}: {}. Raw text:\n{}",
                n, envelope2.error(), rawText2);

        // Both attempts produced invalid JSON — WASTED, run continues.
        return new DecideResult(null,
                "Decision rejected after retry: " + envelope2.error(),
                false, totalTokensIn, totalTokensOut, rawText2);
    }

    // -------------------------------------------------------------------------
    // Infrastructure retry (Track 1, D3): 3 attempts, 30 s / 60 s backoff.
    // Returns an InfraResult — ok=false means all attempts exhausted.
    // -------------------------------------------------------------------------

    private InfraResult callWithInfraRetry(String userPrompt, List<Object> tools) {
        long[] backoffs = {0L, BACKOFF_FIRST_MS, BACKOFF_SECOND_MS};
        String lastError = null;

        for (int attempt = 0; attempt < INFRA_MAX_ATTEMPTS; attempt++) {
            if (backoffs[attempt] > 0) {
                try {
                    sleeper.sleep(backoffs[attempt]);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return InfraResult.fail("Interrupted during backoff before attempt " + (attempt + 1), 0, 0);
                }
            }
            try {
                ChatResult r = chatPort.chat(SystemPrompts.SYSTEM, userPrompt, tools);
                return InfraResult.ok(r.text(), r.tokensIn(), r.tokensOut());
            } catch (Exception e) {
                lastError = e.getMessage();
            }
        }
        return InfraResult.fail("Infra failure after " + INFRA_MAX_ATTEMPTS + " attempts: " + lastError, 0, 0);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void logLlmResp(long tokensIn, long tokensOut, String rawText) {
        BigDecimal cost = computeCost(tokensIn, tokensOut);
        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("tokensIn", tokensIn);
        payload.put("tokensOut", tokensOut);
        if (rawText != null && !rawText.isBlank()) {
            payload.put("rawText", rawText.length() > 2000 ? rawText.substring(0, 2000) + "..." : rawText);
        }
        trajectory.createTrajectoryEvent(runId, "LLM_RESP", payload, tokensIn, tokensOut, cost);
    }

    private BigDecimal computeCost(long tokensIn, long tokensOut) {
        // Prices are per million tokens (per-Mtok). Zero by default until billing data (C13).
        if (loopConfig.priceInPerMtok().compareTo(BigDecimal.ZERO) == 0
                && loopConfig.priceOutPerMtok().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal inCost  = loopConfig.priceInPerMtok()
                .multiply(BigDecimal.valueOf(tokensIn))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
        BigDecimal outCost = loopConfig.priceOutPerMtok()
                .multiply(BigDecimal.valueOf(tokensOut))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    /**
     * Extracts JSON content from raw LLM output (§5.7).
     * Handles:
     * 1. Markdown code fences with prose before/after (e.g. "The analysis shows...\n```json\n{...}\n```")
     * 2. Direct code fences (e.g. "```json\n{...}\n```")
     * 3. Unfenced JSON surrounded by prose (e.g. "The decision is: {...}")
     * 4. Pure raw JSON (e.g. "{...}")
     */
    static String stripFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        // 1. Look for ```[json] ... ``` anywhere in the response
        int fenceStart = s.indexOf("```");
        if (fenceStart != -1) {
            int afterFence = s.indexOf('\n', fenceStart);
            if (afterFence != -1) {
                int fenceEnd = s.indexOf("```", afterFence);
                if (fenceEnd != -1) {
                    String inside = s.substring(afterFence + 1, fenceEnd).trim();
                    int firstBrace = inside.indexOf('{');
                    int lastBrace  = inside.lastIndexOf('}');
                    if (firstBrace != -1 && lastBrace >= firstBrace) {
                        return inside.substring(firstBrace, lastBrace + 1).trim();
                    }
                    if (!inside.isEmpty()) {
                        return inside;
                    }
                }
            }
        }

        // 2. If no valid code fence or fence didn't contain braces, locate the outermost JSON object
        int firstBrace = s.indexOf('{');
        int lastBrace  = s.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace >= firstBrace) {
            return s.substring(firstBrace, lastBrace + 1).trim();
        }

        return s;
    }

    // -------------------------------------------------------------------------
    // Private carrier — avoids returning null from callWithInfraRetry
    // -------------------------------------------------------------------------

    private record InfraResult(boolean ok, String text, String error, long tokensIn, long tokensOut) {
        static InfraResult ok(String text, long tokensIn, long tokensOut) {
            return new InfraResult(true, text, null, tokensIn, tokensOut);
        }
        static InfraResult fail(String error, long tokensIn, long tokensOut) {
            return new InfraResult(false, null, error, tokensIn, tokensOut);
        }
    }
}
