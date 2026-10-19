package io.diag.agent.advisor;

import io.diag.agent.advisor.DiagnosticAdvisorContracts.ContextKeys;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.Orders;
import io.diag.agent.loop.LlmDebugLogger;
import io.diag.evidence.service.EvidenceService;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * LEVEL 40: Pre/Post-Call Trajectory Telemetry (LLM_REQ / LLM_RESP) & Debug Logger Advisor.
 *
 * Practice Reference: phase04-ex01 AuditLoggingAdvisor.
 * Replaces manual trajectory.createTrajectoryEvent("LLM_REQ" / "LLM_RESP"),
 * LlmDebugLogger.log(...) calls in SpringAiDecideTurn, and System.out.println in SpringAiChatPort.
 */
public class TelemetryAndAuditAdvisor implements CallAdvisor {

    private final EvidenceService evidenceService;

    public TelemetryAndAuditAdvisor(EvidenceService evidenceService) {
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "ChatClientRequest must not be null");
        Objects.requireNonNull(chain, "CallAdvisorChain must not be null");

        Map<String, @Nullable Object> context = request.context();
        String runId = String.valueOf(context.getOrDefault(ContextKeys.RUN_ID,"unknown-run"));
        Integer iteration = (Integer) context.getOrDefault(ContextKeys.ITERATION, 0);
        BigDecimal callCost = (BigDecimal) context.getOrDefault(ContextKeys.CALL_COST_USD, BigDecimal.ZERO);

        long now = System.currentTimeMillis();
        evidenceService.createTrajectoryEvent(runId,"LLM_REQ", Map.of("iteration",iteration),0L,0L, BigDecimal.ZERO);
        LlmDebugLogger.log(runId, "LLM PROMPT (Iteration " + iteration + ")", request.prompt().getContents());

        ChatClientResponse response = chain.nextCall(request);

        String rawText = getResult(response);
        if(rawText.length() > 2000){
            rawText = rawText.substring(0, 2000) + "...";
        }

        long tokenIn = 0;
        long tokenOut = 0;
        if(response.chatResponse() != null){
            tokenIn = response.chatResponse().getMetadata().getUsage().getPromptTokens();
            tokenOut = response.chatResponse().getMetadata().getUsage().getCompletionTokens();
        }
        long elapse = System.currentTimeMillis() - now;
        Map<String, Object> payload = Map.of(
                "iteration", iteration,
                "tokensIn", tokenIn,
                "tokensOut", tokenOut,
                "rawText", rawText,
                "cost", callCost,
                "elapse", elapse
        );

        evidenceService.createTrajectoryEvent(runId,"LLM_RESP",payload,tokenIn,tokenOut,callCost);
        LlmDebugLogger.log(runId, "LLM RESPONSE (Iteration " + iteration + ")", rawText);

        return response.mutate().context(ContextKeys.LATENCY_MS,elapse).build();
    }
    private String getResult(ChatClientResponse response) {
        if (response.chatResponse() != null && response.chatResponse().getResult() != null) {
            String text = response.chatResponse().getResult().getOutput().getText();
            return text != null ? text : "";
        }
        return "";
    }

    @Override
    public int getOrder() {
        return Orders.TELEMETRY_AND_AUDIT;
    }

    @Override
    public String getName() {
        return "TelemetryAndAuditAdvisor";
    }
}
