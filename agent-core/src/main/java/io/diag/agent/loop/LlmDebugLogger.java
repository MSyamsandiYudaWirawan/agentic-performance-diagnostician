package io.diag.agent.loop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * File and console logger for LLM prompts, tool executions, and model responses (§5.4).
 * Writes chronologically to:
 *   evidence/artifacts/<runId>/llm-debug.log  (per-run audit trail)
 *   evidence/artifacts/llm-latest.log          (quick reference for the latest active run)
 *
 * Controlled by -Ddiag.debug=true or DIAG_DEBUG=true for console mirroring.
 */
public final class LlmDebugLogger {

    private static final Logger log = LoggerFactory.getLogger(LlmDebugLogger.class);
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private LlmDebugLogger() {}

    private static boolean isDebugEnabled() {
        return log.isDebugEnabled()
                || Boolean.getBoolean("diag.debug")
                || "true".equalsIgnoreCase(System.getenv("DIAG_DEBUG"));
    }

    private static Path resolveArtifactsRoot() {
        String prop = System.getProperty("evidence.artifacts-root");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop).toAbsolutePath().normalize();
        }
        Path current = Path.of("").toAbsolutePath().normalize();
        if (current.getFileName() != null && current.getFileName().toString().equals("agent-core")) {
            Path parent = current.getParent();
            if (parent != null) {
                return parent.resolve("evidence/artifacts").toAbsolutePath().normalize();
            }
        }
        return Path.of("evidence/artifacts").toAbsolutePath().normalize();
    }

    public static void log(String runId, String tag, String message) {
        String timestamp = ISO_FORMATTER.format(Instant.now());
        String content = message == null ? "(null)" : message;
        String entry = "[" + timestamp + "] [DIAG DEBUG: " + tag + "]\n" + content + "\n\n";

        if (isDebugEnabled()) {
            System.out.println("\n========== [DIAG DEBUG: " + tag + "] ==========");
            System.out.println(content);
            System.out.println("================================================\n");
        }

        if (runId != null && !runId.isBlank()) {
            try {
                Path artifactsRoot = resolveArtifactsRoot();
                Path runDir = artifactsRoot.resolve(runId);
                Files.createDirectories(runDir);

                Path runLog = runDir.resolve("llm-debug.log");
                Files.writeString(runLog, entry, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);

                Path latestLog = artifactsRoot.resolve("llm-latest.log");
                Files.writeString(latestLog, entry, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                log.warn("Failed to write to LLM debug log for run {}: {}", runId, e.getMessage());
            }
        }
    }
}
