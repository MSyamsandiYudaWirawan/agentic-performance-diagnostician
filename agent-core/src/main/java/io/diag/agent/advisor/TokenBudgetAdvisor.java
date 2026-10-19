package io.diag.agent.advisor;

import io.diag.agent.advisor.DiagnosticAdvisorContracts.BudgetExceededException;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.Orders;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.TokenBudgetSnapshot;
import io.diag.agent.loop.LoopConfig;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LEVEL 30: Pre-Call Token/Cost Circuit Breaker & Post-Call Usage Accumulator Advisor.
 *
 * Practice Reference: phase04-ex01 TokenBudgetAdvisor & CostCircuitBreakerAdvisor.
 * Eliminates duplicated computeCost() in AgentLoop and SpringAiDecideTurn, and manual Usage
 * unpacking in SpringAiChatPort.
 */
public class TokenBudgetAdvisor implements CallAdvisor {

    private final LoopConfig loopConfig;
    private final AtomicLong tokensIn = new AtomicLong(0L);
    private final AtomicLong tokensOut = new AtomicLong(0L);
    private final AtomicReference<BigDecimal> costUsd = new AtomicReference<>(BigDecimal.ZERO);
    private final AtomicInteger callCount = new AtomicInteger(0);

    public TokenBudgetAdvisor(LoopConfig loopConfig) {
        this.loopConfig = Objects.requireNonNull(loopConfig, "loopConfig must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "ChatClientRequest must not be null");
        Objects.requireNonNull(chain, "CallAdvisorChain must not be null");
        if (tokensIn.get() + tokensOut.get() >= loopConfig.maxTokens()) {
            throw new BudgetExceededException("Token cap (" + loopConfig.maxTokens() + ") exceeded");
        }
        if (loopConfig.maxCostUsd().compareTo(BigDecimal.ZERO) > 0 && costUsd.get().compareTo(loopConfig.maxCostUsd()) >= 0) {
            throw new BudgetExceededException("Cost cap ($" + loopConfig.maxCostUsd() + ") exceeded");
        }

        ChatClientResponse response = chain.nextCall(request);
        callCount.incrementAndGet();

        long tokenIn = 0;
        long tokenOut = 0;
        if(response.chatResponse() != null){
            tokenIn= response.chatResponse().getMetadata().getUsage().getPromptTokens();
            tokenOut = response.chatResponse().getMetadata().getUsage().getCompletionTokens();

            tokensIn.addAndGet(tokenIn);
            tokensOut.addAndGet(tokenOut);
        }
        BigDecimal cost = computeCost(tokenIn, tokenOut);
        if(cost.compareTo(BigDecimal.ZERO) > 0){
            costUsd.updateAndGet(prev -> prev.add(cost));
        }

        return response.mutate().context(DiagnosticAdvisorContracts.ContextKeys.CALL_COST_USD,cost).build();
    }

    /**
     * Single source of truth for per-Mtok USD cost calculation.
     */
    public BigDecimal computeCost(long in, long out) {
        return computeCost(this.loopConfig, in, out);
    }

    /**
     * Static per-Mtok USD cost calculator shared across advisors and loop components.
     */
    public static BigDecimal computeCost(LoopConfig loopConfig, long in, long out) {
        Objects.requireNonNull(loopConfig, "loopConfig must not be null");
        if (loopConfig.priceInPerMtok().compareTo(BigDecimal.ZERO) == 0
                && loopConfig.priceOutPerMtok().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal inCost = loopConfig.priceInPerMtok()
                .multiply(BigDecimal.valueOf(in))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
        BigDecimal outCost = loopConfig.priceOutPerMtok()
                .multiply(BigDecimal.valueOf(out))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    public TokenBudgetSnapshot getSnapshot() {
        long in = tokensIn.get();
        long out = tokensOut.get();
        return new TokenBudgetSnapshot(in, out, in + out, costUsd.get(), callCount.get());
    }

    public void reset() {
        tokensIn.set(0L);
        tokensOut.set(0L);
        costUsd.set(BigDecimal.ZERO);
        callCount.set(0);
    }

    @Override
    public int getOrder() {
        return Orders.TOKEN_BUDGET;
    }

    @Override
    public String getName() {
        return "TokenBudgetAdvisor";
    }
}
