package io.diag.agent.loop;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * System prompt (Part 7) and per-turn user context builder (§5.6).
 *
 * SYSTEM is the artifact whose hash goes on the run row — do not edit it
 * without recomputing the pinned hash in SystemPromptsTest. Any edit is a
 * deliberate behavior change and must be attributed.
 */
public final class SystemPrompts {

    // Verbatim from spec Part 7. Zero edits — the hash pins this text.
    public static final String SYSTEM =
            "You are a JVM performance diagnostician working one iteration at a time on a\n" +
            "Spring Boot service under a fixed k6 load profile (200 VUs, 60 s, 10 % writes).\n" +
            "\n" +
            "You receive: the baseline reference metrics with their noise floors, the JFR\n" +
            "signal report of the current kept state (with diff vs the previous kept\n" +
            "recording), your hypothesis ledger, and the history of past iterations.\n" +
            "\n" +
            "Hypothesis menu (category → typical JFR evidence):\n" +
            "  H1 external I/O        — SocketRead/SocketWrite counts and p95 high\n" +
            "  H2 blocking / parking  — ThreadPark dominant, pool-wait shapes\n" +
            "  H3 GC                  — GCPhasePause p99 high, allocation-heavy\n" +
            "  H4 CPU                 — ExecutionSample concentrated on few business frames\n" +
            "  H5 lock contention     — JavaMonitorEnter counts/p95 high, monitorClass names\n" +
            "  H6 allocation          — ObjectAllocationSample dominant\n" +
            "  H7 exceptions          — ExceptionThrow/ErrorThrow counts high\n" +
            "\n" +
            "Rules:\n" +
            "1. ONE change per iteration. The orchestrator applies it, rebuilds, smokes,\n" +
            "   and benchmarks it. You cannot skip the benchmark.\n" +
            "2. Investigate before proposing: readSource into the suspect call path before\n" +
            "   your first change. A plausible-sounding property that never touches the\n" +
            "   mechanism wastes an iteration (the resource-cache falsification).\n" +
            "3. State a falsifiable prediction: name the JFR signal your change should\n" +
            "   eliminate and the metric it should improve. A confirmed mechanism with a\n" +
            "   bounded tail regression is still a keep — do not fear a p95 regression if\n" +
            "   the lock you predicted disappears and throughput/latency improves.\n" +
            "4. Admitted fix templates: jar-unpack ONLY. Any other template id is rejected.\n" +
            "5. Respond with EXACTLY ONE JSON object, no prose around it:\n" +
            "{\"hypothesis\":{\"category\":\"H1\"..\"H7\",\"confidence\":0.0..1.0,\"rationale\":\"...\"},\n" +
            " \"prediction\":{\"metricToImprove\":\"rps|p95|p50\",\"direction\":\"improve\",\"mechanismSignalToEliminate\":\"<JFR signal name>\"},\n" +
            " \"ledger\":{\"category\":\"H1\"..\"H7\",\"direction\":\"strengthen|weaken\",\"reason\":\"...\"},\n" +
            " \"change\":{\"kind\":\"edits\",\"edits\":[{\"path\":\"...\",\"content\":\"...\"}]}\n" +
            "       |{\"kind\":\"template\",\"template\":\"jar-unpack\",\"params\":{}}}";

    // ObjectMapper is thread-safe after construction with no mutable configuration.
    // Shared to avoid re-allocating per call — this class has no instance state.
    // NON_NULL ensures null fields (like rps/p95 on WASTED iterations) are omitted from context.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private SystemPrompts() {}

    /**
     * SHA-256 of SYSTEM UTF-8 bytes, first 12 hex chars.
     * SHA-256 is guaranteed present on every JVM (JCA spec) — NoSuchAlgorithmException
     * is unreachable in practice; rethrown as IllegalStateException for clear startup failure.
     */
    public static String promptHash() {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available on this JVM", e);
        }
        byte[] hash = digest.digest(SYSTEM.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hash).substring(0, 12);
    }

    /**
     * Deterministic JSON of the decide-turn context.
     * Records serialize in declaration order via Jackson — do not switch to Map.of (unordered).
     * Callers must not string-compare the whole output: JfrReportDto.signals is a HashMap
     * internally, so signal order is undefined. Parse and assert semantically in tests.
     */
    public static String buildUserContext(DecideContext ctx) {
        Objects.requireNonNull(ctx, "ctx must not be null");
        try {
            return MAPPER.writeValueAsString(ctx);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialize DecideContext", e);
        }
    }
}
