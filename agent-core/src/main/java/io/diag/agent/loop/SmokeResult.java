package io.diag.agent.loop;

import java.util.Objects;

public record SmokeResult(boolean pass, String detail) {
    public SmokeResult {
        Objects.requireNonNull(detail, "detail must not be null");
    }
}
