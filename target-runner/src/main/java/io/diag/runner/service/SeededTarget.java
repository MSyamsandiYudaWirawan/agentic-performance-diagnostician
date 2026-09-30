package io.diag.runner.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Manages the lifecycle of a Seeded Target (Step 10 M1, scope §3, §10.23, §10.33).
 * Applies committed degradation patches on top of pristine base clones, commits them as baselineSha,
 * and handles isolation and reset via ChangeApplier.revertTo().
 */
public final class SeededTarget {

    private static final int GIT_TIMEOUT_SECONDS = 60;
    private final ChangeApplier changeApplier;

    public SeededTarget(ChangeApplier changeApplier) {
        this.changeApplier = Objects.requireNonNull(changeApplier, "changeApplier must not be null");
    }

    /**
     * Seeds the target repository from pristine base with the specified patch file.
     *
     * @param targetRepo  working tree of target repo
     * @param pristineSha base commit SHA before any seed
     * @param patchFile   patch file to apply (or null if target is pristine, e.g. S1)
     * @param targetId    target identifier (e.g. S2, S3)
     * @param description human-readable description of the seed
     * @return the resulting baselineSha of the seeded target
     */
    public String seed(Path targetRepo, String pristineSha, Path patchFile, String targetId, String description)
            throws Exception {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(pristineSha, "pristineSha must not be null");
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(description, "description must not be null");

        // Step 1: Revert to pristine base first
        changeApplier.revertTo(targetRepo, pristineSha);

        // Step 2: S1 or patch-less target needs no mutation
        if (patchFile == null) {
            return changeApplier.currentSha(targetRepo);
        }

        if (!Files.exists(patchFile)) {
            throw new IllegalArgumentException("Seed patch file does not exist: " + patchFile);
        }

        // Step 3: Apply patch using git apply
        String patchPathStr = patchFile.toAbsolutePath().toString().replace('\\', '/');
        git(targetRepo, "apply", "--ignore-whitespace", patchPathStr);

        // Step 4: Add all changes and commit
        git(targetRepo, "add", "-A");
        String commitMsg = "[seed] " + targetId + ": " + description;
        git(targetRepo, "commit", "-m", commitMsg);

        // Step 5: Verify working tree hygiene (§10.33)
        String status = gitOutput(targetRepo, "status", "--porcelain");
        if (!status.trim().isEmpty()) {
            throw new IllegalStateException("Working tree not clean after seeding " + targetId + ":\n" + status);
        }

        return changeApplier.currentSha(targetRepo);
    }

    /**
     * Resets target repository to its seeded baselineSha.
     */
    public void reset(Path targetRepo, String baselineSha) throws IOException, InterruptedException {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(baselineSha, "baselineSha must not be null");
        changeApplier.revertTo(targetRepo, baselineSha);
    }

    /**
     * Resets target repository back to pristine base commit SHA.
     */
    public void resetToPristine(Path targetRepo, String pristineSha) throws IOException, InterruptedException {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(pristineSha, "pristineSha must not be null");
        changeApplier.revertTo(targetRepo, pristineSha);
    }

    private void git(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);

        Process p = pb.start();
        boolean finished = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (exit " + p.exitValue() + "): " + output);
        }
    }

    private String gitOutput(Path dir, String... args) throws IOException, InterruptedException {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true);

        Process p = pb.start();
        boolean finished = p.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        String output = new String(p.getInputStream().readAllBytes());
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed (exit " + p.exitValue() + "): " + output);
        }
        return output;
    }
}
