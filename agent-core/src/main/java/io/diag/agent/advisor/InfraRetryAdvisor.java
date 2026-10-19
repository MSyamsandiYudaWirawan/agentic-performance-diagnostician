package io.diag.agent.advisor;

import io.diag.agent.advisor.DiagnosticAdvisorContracts.InfraRetryExhaustedException;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.Orders;
import io.diag.agent.advisor.DiagnosticAdvisorContracts.Sleeper;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;

import java.util.Objects;

/**
 * LEVEL 50 (Innermost): Track-1 Infrastructure Retry & Backoff Advisor.
 * <p>
 * Practice Reference: phase04-ex02 ModelFailoverAdvisor / CircuitBreakerFailoverAdvisor.
 * Replaces SpringAiDecideTurn.callWithInfraRetry(...) with 3 attempts (0s, 30s, 60s backoff).
 */
public class InfraRetryAdvisor implements CallAdvisor {

    public static final int DEFAULT_MAX_ATTEMPTS = 3;
    public static final long[] DEFAULT_BACKOFF_MS = {0L, 30_000L, 60_000L};

    private final int maxAttempts;
    private final long[] backoffsMs;
    private final Sleeper sleeper;

    public InfraRetryAdvisor() {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS, Thread::sleep);
    }

    public InfraRetryAdvisor(Sleeper sleeper) {
        this(DEFAULT_MAX_ATTEMPTS, DEFAULT_BACKOFF_MS, sleeper);
    }

    public InfraRetryAdvisor(int maxAttempts, long[] backoffsMs, Sleeper sleeper) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be >= 1");
        }
        this.maxAttempts = maxAttempts;
        this.backoffsMs = Objects.requireNonNull(backoffsMs, "backoffsMs must not be null");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper must not be null");
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        Objects.requireNonNull(request, "ChatClientRequest must not be null");
        Objects.requireNonNull(chain, "CallAdvisorChain must not be null");

        Exception lastException = null;

        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                if (backoffsMs[attempt] > 0) {
                    sleeper.sleep(backoffsMs[attempt]);
                }
                return chain.copy(this).nextCall(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InfraRetryExhaustedException("Interrupted during backoff before attempt " + (attempt + 1), e);
            } catch (Exception e) {
                lastException = e;
            }
        }

        throw new InfraRetryExhaustedException("Infra failure after " + maxAttempts + " attempts: " + lastException.getMessage(), lastException);
    }

    @Override
    public int getOrder() {
        return Orders.INFRA_RETRY;
    }

    @Override
    public String getName() {
        return "InfraRetryAdvisor";
    }
}
