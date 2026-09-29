package io.diag.agent.loop;

import java.util.Objects;

public record ChatResult(
        String text,
        long tokensIn,
        long tokensOut
) {
    public ChatResult{
        Objects.requireNonNull(text, "text must not be null");
        if (tokensIn < 0)  throw new IllegalArgumentException("tokensIn must be >= 0, got " + tokensIn);
        if (tokensOut < 0) throw new IllegalArgumentException("tokensOut must be >= 0, got " + tokensOut);
    }
}
