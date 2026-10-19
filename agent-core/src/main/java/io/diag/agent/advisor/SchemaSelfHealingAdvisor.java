package io.diag.agent.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.agent.ToolEnvelope;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.ContextKeys;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.Orders;
import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.DecisionValidator;
import io.diag.agent.loop.FallbackDecisionExtractor;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.Objects;

/**
 * LEVEL 20: Post-Call JSON Fence Stripping, Decision Validation, Fallback Extraction,
 * and Track-2 One-Shot Self-Healing Retry Advisor.
 * <p>
 * Practice Reference: phase04-ex02 Drill 10 (SchemaSelfHealingAdvisor).
 * Replaces ~150 lines of duplicated Attempt 1 / Attempt 2 validation in SpringAiDecideTurn.
 */
public class SchemaSelfHealingAdvisor implements CallAdvisor {

    private final DecisionValidator validator;
    private final ObjectMapper mapper;

    public SchemaSelfHealingAdvisor(DecisionValidator validator, ObjectMapper mapper) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "ChatClientRequest must not be null");
        Objects.requireNonNull(chain, "CallAdvisorChain must not be null");

        ChatClientRequest newRequest = request.mutate()
                .context(ContextKeys.ATTEMPT, 1)
                .build();
        ChatClientResponse response = chain.nextCall(newRequest);
        String rawText = getResult(response);

        ToolEnvelope<DecisionDto> envelope = validateOrFallback(rawText);
        if (envelope.ok()) {
            return response.mutate()
                    .context(ContextKeys.SCHEMA_HEALED, false)
                    .context(ContextKeys.VALIDATED_DECISION, envelope.data())
                    .build();
        }
        String newPrompt = buildRetryPrompt(request.prompt().getContents(), rawText, envelope.error());
        ChatClientRequest retryRequest = request.mutate()
                .prompt(new Prompt(newPrompt))
                .context(ContextKeys.ATTEMPT, 2)
                .build();

        ChatClientResponse retryResponse = chain.copy(this).nextCall(retryRequest);
        String rawText2 = getResult(retryResponse);
        ToolEnvelope<DecisionDto> envelope2 = validateOrFallback(rawText2);
        if(envelope2.ok()) {
            return retryResponse.mutate()
                    .context(ContextKeys.VALIDATED_DECISION, envelope2.data())
                    .context(ContextKeys.SCHEMA_HEALED, true)
                    .build();
        }

        return retryResponse.mutate()
                .context(ContextKeys.VALIDATION_ERROR, "Decision rejected after retry: " + envelope2.error())
                .build();
    }

    private String getResult(ChatClientResponse response) {
        if (response.chatResponse() != null && response.chatResponse().getResult() != null) {
            String text = response.chatResponse().getResult().getOutput().getText();
            return text != null ? text : "";
        }
        return "";
    }

    public static String buildRetryPrompt(String originalPrompt, String rawText, String validationError) {
        if (rawText != null && !rawText.isBlank()) {
            return "[CRITICAL ERROR: Your previous decision was rejected because you did not output valid JSON.\n"
                    + "Validation error: " + validationError + "]\n\n"
                    + "Your previous analysis was:\n"
                    + "\"\"\"\n"
                    + (rawText.length() > 2500 ? rawText.substring(0, 2500) + "..." : rawText) + "\n"
                    + "\"\"\"\n\n"
                    + "Convert your diagnosis above into this exact JSON format:\n"
                    + "{\n"
                    + "  \"hypothesis\": {\n"
                    + "    \"category\": \"H5\",\n"
                    + "    \"confidence\": 0.9,\n"
                    + "    \"rationale\": \"<summary of your findings>\"\n"
                    + "  },\n"
                    + "  \"prediction\": {\n"
                    + "    \"metricToImprove\": \"p95\",\n"
                    + "    \"direction\": \"improve\",\n"
                    + "    \"mechanismSignalToEliminate\": \"JavaMonitorEnter\"\n"
                    + "  },\n"
                    + "  \"ledger\": {\n"
                    + "    \"category\": \"H5\",\n"
                    + "    \"direction\": \"strengthen\",\n"
                    + "    \"reason\": \"<reason from your analysis>\"\n"
                    + "  },\n"
                    + "  \"change\": {\n"
                    + "    \"kind\": \"template\",\n"
                    + "    \"template\": \"jar-unpack\",\n"
                    + "    \"params\": {}\n"
                    + "  }\n"
                    + "}\n"
                    + "OUTPUT THE RAW JSON OBJECT NOW:";
        }
        return originalPrompt
                + "\n\n[CRITICAL ERROR: Your previous decision was rejected with error:\n"
                + validationError
                + "\nYou must output ONLY valid JSON starting with '{' and ending with '}'. "
                + "OUTPUT THE RAW JSON OBJECT NOW:]";
    }

    /**
     * Strips markdown fences, validates via {@link DecisionValidator}, and falls back to
     * {@link FallbackDecisionExtractor} if needed.
     */
    public ToolEnvelope<DecisionDto> validateOrFallback(String rawText) {
        String stripped = stripFences(rawText);
        ToolEnvelope<DecisionDto> envelope = validator.validate(stripped);
        if (!envelope.ok()) {
            DecisionDto fallback = FallbackDecisionExtractor.tryExtract(rawText);
            if (fallback != null) {
                try {
                    String fallbackJson = mapper.writeValueAsString(fallback);
                    ToolEnvelope<DecisionDto> fallbackEnvelope = validator.validate(fallbackJson);
                    if (fallbackEnvelope.ok()) {
                        return fallbackEnvelope;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return envelope;
    }

    public static String stripFences(String raw) {
        if (raw == null) return "";
        String s = raw.trim();

        int fenceStart = s.indexOf("```");
        if (fenceStart != -1) {
            int afterFence = s.indexOf('\n', fenceStart);
            if (afterFence != -1) {
                int fenceEnd = s.indexOf("```", afterFence);
                if (fenceEnd != -1) {
                    String inside = s.substring(afterFence + 1, fenceEnd).trim();
                    int firstBrace = findRootJsonStart(inside);
                    int lastBrace = inside.lastIndexOf('}');
                    if (firstBrace != -1 && lastBrace >= firstBrace) {
                        return inside.substring(firstBrace, lastBrace + 1).trim();
                    }
                    if (!inside.isEmpty()) {
                        return inside;
                    }
                }
            }
        }

        int firstBrace = findRootJsonStart(s);
        int lastBrace = s.lastIndexOf('}');
        if (firstBrace != -1 && lastBrace >= firstBrace) {
            return s.substring(firstBrace, lastBrace + 1).trim();
        }
        return s;
    }

    private static int findRootJsonStart(String text) {
        int hypIndex = text.indexOf("\"hypothesis\"");
        if (hypIndex != -1) {
            int braceBeforeHyp = text.lastIndexOf('{', hypIndex);
            if (braceBeforeHyp != -1) {
                return braceBeforeHyp;
            }
        }
        return text.indexOf('{');
    }

    @Override
    public int getOrder() {
        return Orders.SCHEMA_SELF_HEALING;
    }

    @Override
    public String getName() {
        return "SchemaSelfHealingAdvisor";
    }
}
