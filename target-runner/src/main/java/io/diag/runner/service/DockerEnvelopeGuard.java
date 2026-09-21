package io.diag.runner.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Real envelope assertion: the target container's effective resource budget must
 * equal the envelope's 2 CPU / 2 GB — catches any target-tree edit that moved the
 * caps, whatever file path it took (scope §10.28).
 */
public final class DockerEnvelopeGuard implements EnvelopeGuard {
    private static final long BUDGET_NANO_CPUS = 2_000_000_000L; // 2 CPUs
    private static final long BUDGET_MEMORY = 2_147_483_648L;    // 2 GB

    @Override
    public void assertBudget(String containerName) throws IOException, InterruptedException {
        Objects.requireNonNull(containerName, "containerName must not be null");

        Process p = new ProcessBuilder(
                "docker", "inspect", containerName,
                "--format", "{{.HostConfig.NanoCpus}} {{.HostConfig.Memory}}")
                .redirectErrorStream(true)
                .start();

        boolean done = p.waitFor(15, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            throw new IllegalStateException("docker inspect timed out for " + containerName);
        }

        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();

        // a missing container or daemon error is an infrastructure failure, never a
        // benchmark result — fail loudly with the raw output instead of letting it
        // surface later as a NumberFormatException (TIGERSTYLE §2)
        if (p.exitValue() != 0) {
            throw new IllegalStateException(
                    "docker inspect failed (exit " + p.exitValue() + ") for " + containerName + ": " + out);
        }

        String[] parts = out.split("\\s+");
        if (parts.length != 2) {
            throw new IllegalStateException(
                    "unexpected docker inspect output for " + containerName + ": '" + out + "'");
        }

        long nanoCpus;
        long memory;
        try {
            nanoCpus = Long.parseLong(parts[0]);
            memory = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(
                    "unparseable docker inspect output for " + containerName + ": '" + out + "'", e);
        }

        if (nanoCpus != BUDGET_NANO_CPUS || memory != BUDGET_MEMORY) {
            throw new IllegalStateException(
                    "envelope tampered: NanoCpus=" + nanoCpus + " Memory=" + memory +
                            " (expected " + BUDGET_NANO_CPUS + " / " + BUDGET_MEMORY + ")");
        }
    }
}
