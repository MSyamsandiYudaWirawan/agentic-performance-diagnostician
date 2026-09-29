package io.diag.agent.loop;

import io.diag.agent.ToolEnvelope;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.service.EvidenceService;
import org.springframework.ai.tool.annotation.Tool;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turn-scoped wrapper around DiagnosticTools.readSource (§5.5, D1, D5).
 * Constructed fresh per decide turn — single-threaded by construction, so
 * a plain int counter is correct (no AtomicInteger needed or wanted).
 *
 * Beyond the bound, the model gets a fail envelope instead of a result —
 * it is cut off, not paid for (§10.34). The TOOL_CALL row is always logged
 * first so the trajectory is complete even for rejected calls.
 */
public class BoundedReadSource {

    private final DiagnosticTools tools;
    private final int bound;
    private final EvidenceService trajectory;
    private final String runId;
    private final String fullContext;
    private int callCount = 0;

    public BoundedReadSource(DiagnosticTools tools, int bound,
                             EvidenceService trajectory, String runId) {
        this(tools, bound, trajectory, runId, null);
    }

    public BoundedReadSource(DiagnosticTools tools, int bound,
                             EvidenceService trajectory, String runId,
                             String fullContext) {
        this.tools       = Objects.requireNonNull(tools,      "tools must not be null");
        this.trajectory  = Objects.requireNonNull(trajectory, "trajectory must not be null");
        this.runId       = Objects.requireNonNull(runId,      "runId must not be null");
        if (bound < 1) throw new IllegalArgumentException("bound must be >= 1, got " + bound);
        this.bound       = bound;
        this.fullContext = fullContext;
    }

    private static boolean isDebugEnabled() {
        return Boolean.getBoolean("diag.debug")
                || "true".equalsIgnoreCase(System.getenv("DIAG_DEBUG"));
    }

    @Tool(description = """
            Recall the complete diagnostic context for this iteration, including
            reference load metrics, noise floors, full JFR signals with top frames,
            hypothesis ledger, and past iteration history.
            Use this if you need to review the diagnostic data and previous outcomes again.
            """)
    public ToolEnvelope<String> recallContext() {
        callCount++;

        String callMsg = "recallContext() [call " + callCount + "/" + bound + "]";
        LlmDebugLogger.log(runId, "TOOL CALL", callMsg);

        trajectory.createTrajectoryEvent(runId, "TOOL_CALL",
                Map.of("action", "recallContext"), null, null, null);

        if (callCount > bound) {
            String msg = "tool-call bound (" + bound + ") exceeded — decide now";
            LlmDebugLogger.log(runId, "TOOL RESULT", msg);
            trajectory.createTrajectoryEvent(runId, "TOOL_RESULT",
                    Map.of("ok", false, "error", msg), null, null, null);
            return ToolEnvelope.fail(msg);
        }

        String ctx = fullContext != null ? fullContext : "No context available";
        LlmDebugLogger.log(runId, "TOOL RESULT", "recallContext() -> (" + ctx.length() + " chars)");

        trajectory.createTrajectoryEvent(runId, "TOOL_RESULT",
                Map.of("ok", true, "bytes", ctx.length()), null, null, null);

        return ToolEnvelope.ok(ctx);
    }

    @Tool(description = """
            Recall the full diagnostic context for this iteration.
            Alias for recallContext().
            """)
    public ToolEnvelope<String> recallFullContext() {
        return recallContext();
    }

    @Tool(description = """
            Recall the repository structure (file listing) of the target repository.
            Use this if you need to review which configuration, source, or build files exist.
            """)
    public ToolEnvelope<List<String>> listRepositoryStructure() {
        callCount++;

        String callMsg = "listRepositoryStructure() [call " + callCount + "/" + bound + "]";
        LlmDebugLogger.log(runId, "TOOL CALL", callMsg);

        trajectory.createTrajectoryEvent(runId, "TOOL_CALL",
                Map.of("action", "listRepositoryStructure"), null, null, null);

        if (callCount > bound) {
            String msg = "tool-call bound (" + bound + ") exceeded — decide now";
            LlmDebugLogger.log(runId, "TOOL RESULT", msg);
            trajectory.createTrajectoryEvent(runId, "TOOL_RESULT",
                    Map.of("ok", false, "error", msg), null, null, null);
            return ToolEnvelope.fail(msg);
        }

        List<String> files = tools.listRepositoryFiles();
        LlmDebugLogger.log(runId, "TOOL RESULT", "listRepositoryStructure() -> " + (files != null ? files.size() : 0) + " files");

        Map<String, Object> resultPayload = Map.of("ok", true, "count", files != null ? files.size() : 0);
        trajectory.createTrajectoryEvent(runId, "TOOL_RESULT", resultPayload, null, null, null);

        return ToolEnvelope.ok(files != null ? files : List.of());
    }

    @Tool(description = """
            Read a file from the target repository. Path must be relative to the
            target root (e.g. src/main/java/Foo.java or Dockerfile.target).
            Returns ok=false if the path escapes the target root, is a symlink out,
            or exceeds the 100 KB cap. Investigate the suspect call path before
            proposing a change — read source before every decision.
            """)
    public ToolEnvelope<String> readSource(String path) {
        callCount++;

        String callMsg = "readSource(\"" + path + "\") [call " + callCount + "/" + bound + "]";
        LlmDebugLogger.log(runId, "TOOL CALL", callMsg);

        // Log TOOL_CALL before any guard — the call happened regardless of outcome.
        trajectory.createTrajectoryEvent(runId, "TOOL_CALL",
                Map.of("path", path == null ? "" : path), null, null, null);

        if (callCount > bound) {
            String msg = "tool-call bound (" + bound + ") exceeded — decide now";
            LlmDebugLogger.log(runId, "TOOL RESULT", msg);
            trajectory.createTrajectoryEvent(runId, "TOOL_RESULT",
                    Map.of("ok", false, "error", msg), null, null, null);
            return ToolEnvelope.fail(msg);
        }

        ToolEnvelope<String> result = tools.readSource(path);

        if (result.ok()) {
            LlmDebugLogger.log(runId, "TOOL RESULT", "readSource(\"" + path + "\") -> OK (" + result.data().length() + " chars)");
        } else {
            LlmDebugLogger.log(runId, "TOOL RESULT", "readSource(\"" + path + "\") -> FAIL: " + result.error());
        }

        Map<String, Object> resultPayload;
        if (result.ok()) {
            resultPayload = Map.of("ok", true, "bytes", result.data().length());
        } else {
            resultPayload = Map.of("ok", false, "error", result.error());
        }
        trajectory.createTrajectoryEvent(runId, "TOOL_RESULT", resultPayload, null, null, null);

        return result;
    }
}
