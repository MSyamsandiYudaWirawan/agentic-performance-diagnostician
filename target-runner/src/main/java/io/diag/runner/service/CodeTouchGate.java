package io.diag.runner.service;

import io.diag.evidence.dto.FilesTouchedList;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Code-touch gate (scope §10.31): if any touched file ends in .java, run the
 * target's own test suite (mvn -B test) before smoke. Config-only edits skip it.
 *
 * Wire-up into the rebuild pipeline is step 9; this class ships the callable pieces.
 */
public final class CodeTouchGate {

    // 15-minute timeout matches TargetBuilder's build timeout — test suites can be slow.
    private static final int TEST_TIMEOUT_MINUTES = 15;

    /**
     * Returns true if any file in {@code touched} ends with {@code .java}.
     * The predicate is evaluated before deciding whether to invoke {@link #runTests}.
     */
    public boolean isJavaTouched(FilesTouchedList touched) {
        Objects.requireNonNull(touched, "touched must not be null");
        for (var file : touched.files()) {
            if (file.path().endsWith(".java")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs {@code mvn -B test} in {@code targetRepo}, redirecting output to {@code logFile}.
     * Called only when {@link #isJavaTouched} returns true.
     *
     * @return true if tests passed (exit 0)
     */
    public boolean runTests(Path targetRepo, Path logFile) throws IOException, InterruptedException {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(logFile,    "logFile must not be null");

        // cmd /c is required here: mvn is a .cmd script on Windows, not an .exe.
        // TargetBuilder uses the same pattern for the same reason.
        Process process = new ProcessBuilder("cmd", "/c", "mvn", "-B", "test")
                .directory(targetRepo.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .start();

        boolean finished = process.waitFor(TEST_TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new BuildFailedException(
                    "mvn test timed out after " + TEST_TIMEOUT_MINUTES + " minutes; log: " + logFile);
        }
        return process.exitValue() == 0;
    }
}
