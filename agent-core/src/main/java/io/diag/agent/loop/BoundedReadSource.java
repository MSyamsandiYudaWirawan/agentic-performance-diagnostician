package io.diag.agent.loop;

import io.diag.agent.ToolEnvelope;
import io.diag.agent.tools.DiagnosticTools;
import io.diag.evidence.service.EvidenceService;
import org.springframework.ai.tool.annotation.Tool;

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
    private int callCount = 0;

    public BoundedReadSource(DiagnosticTools tools, int bound,
                             EvidenceService trajectory, String runId) {
        this.tools      = Objects.requireNonNull(tools,      "tools must not be null");
        this.trajectory = Objects.requireNonNull(trajectory, "trajectory must not be null");
        this.runId      = Objects.requireNonNull(runId,      "runId must not be null");
        if (bound < 1) throw new IllegalArgumentException("bound must be >= 1, got " + bound);
        this.bound = bound;
    }

    private static boolean isDebugEnabled() {
        return Boolean.getBoolean("diag.debug")
                || "true".equalsIgnoreCase(System.getenv("DIAG_DEBUG"));
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

        if (isDebugEnabled()) {
            System.out.println("[DIAG DEBUG: TOOL CALL] readSource(\"" + path + "\") [call " + callCount + "/" + bound + "]");
        }

        // Log TOOL_CALL before any guard — the call happened regardless of outcome.
        trajectory.createTrajectoryEvent(runId, "TOOL_CALL",
                Map.of("path", path == null ? "" : path), null, null, null);

        if (callCount > bound) {
            String msg = "tool-call bound (" + bound + ") exceeded — decide now";
            if (isDebugEnabled()) {
                System.out.println("[DIAG DEBUG: TOOL RESULT] " + msg);
            }
            trajectory.createTrajectoryEvent(runId, "TOOL_RESULT",
                    Map.of("ok", false, "error", msg), null, null, null);
            return ToolEnvelope.fail(msg);
        }

        ToolEnvelope<String> result = tools.readSource(path);

        if (isDebugEnabled()) {
            if (result.ok()) {
                System.out.println("[DIAG DEBUG: TOOL RESULT] readSource(\"" + path + "\") -> OK (" + result.data().length() + " chars)");
            } else {
                System.out.println("[DIAG DEBUG: TOOL RESULT] readSource(\"" + path + "\") -> FAIL: " + result.error());
            }
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
