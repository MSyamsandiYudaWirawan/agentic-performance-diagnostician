package io.diag.agent;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Every tool returns this — the model always gets parseable JSON (§10.4).
 * ok=true  → data is the result; error is null.
 * ok=false → error is the reason; data is null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ToolEnvelope<T>(boolean ok, T data, String error) {

    public static <T> ToolEnvelope<T> ok(T data) {
        return new ToolEnvelope<>(true, data, null);
    }

    public static <T> ToolEnvelope<T> fail(String error) {
        return new ToolEnvelope<>(false, null, error);
    }
}
