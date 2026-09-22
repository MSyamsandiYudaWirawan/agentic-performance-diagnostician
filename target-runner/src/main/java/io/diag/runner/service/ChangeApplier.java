package io.diag.runner.service;

import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.EditDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.runner.config.ChangeResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Safe, machine-checkable mutations of the target tree (scope §4.1, step 7).
 *
 * apply()   — full-file replacement edits or template expansion → git commit → ChangeResult
 * revertTo() — git reset --hard + git clean -fd → assert porcelain empty (§10.13)
 * currentSha() — git rev-parse HEAD (the "track baselineSha" primitive; loop logic is step 9)
 *
 * Plain final class — no Spring, constructor injection only (same idiom as TargetBuilder/TargetStack).
 */
public final class ChangeApplier {

    // §10.3: per-file line cap. Reject pre-apply; tree untouched; no commit.
    static final int LINE_CAP = 400;

    // §10.13: two-pass clean; if still dirty after -fdx, throw.
    private static final int GIT_TIMEOUT_SECONDS = 60;

    private final FixTemplateRegistry registry;

    public ChangeApplier(FixTemplateRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    // -------------------------------------------------------------------------
    // apply
    // -------------------------------------------------------------------------

    /**
     * Applies {@code change} to {@code targetRepo} and commits with {@code description}.
     *
     * kind=edits  — each EditDto is path-confined, line-capped, then written.
     * kind=template — delegates to the registry (admission-gated), emits edits, same path.
     *
     * Rejection (path escape, line cap, unadmitted/unknown template, no-op content) is
     * pre-commit: no commit is produced. Path/line-cap/template rejection leaves the
     * tree untouched; a no-op change (content identical to HEAD) leaves it clean.
     * A failure AFTER the first file write (I/O or git error) can leave a dirty,
     * uncommitted tree — callers recover with {@link #revertTo} (taxonomy row 1).
     */
    public ChangeResult apply(Path targetRepo, ChangeDto change, String description) throws Exception {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(change,     "change must not be null");
        Objects.requireNonNull(description,"description must not be null");

        List<EditDto> edits = resolveEdits(targetRepo, change);

        // Validate ALL edits before writing any — tree stays untouched on rejection.
        for (EditDto edit : edits) {
            validatePath(targetRepo, edit.path());
            validateLineCap(edit);
        }

        List<FilesTouchedDto> touched = new ArrayList<>(edits.size());
        boolean javaTouched = false;

        for (EditDto edit : edits) {
            Path target = targetRepo.resolve(edit.path()).normalize();
            int linesBefore = countLines(target);
            writeFile(target, edit.content());
            int linesAfter  = countLines(target);

            touched.add(new FilesTouchedDto(edit.path(), linesBefore, linesAfter));
            if (edit.path().endsWith(".java")) {
                javaTouched = true;
            }
        }

        String sha = gitCommit(targetRepo, sanitizeDescription(description));
        return new ChangeResult(sha, FilesTouchedList.of(touched), javaTouched);
    }

    // -------------------------------------------------------------------------
    // revertTo
    // -------------------------------------------------------------------------

    /**
     * Hard-resets the target tree to {@code sha} and cleans untracked files.
     * Asserts porcelain output is empty; falls back to {@code git clean -fdx} if not (§10.13).
     */
    public void revertTo(Path targetRepo, String sha) throws IOException, InterruptedException {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(sha,        "sha must not be null");

        git(targetRepo, "reset", "--hard", sha);
        git(targetRepo, "clean", "-fd");

        if (!isPorcelainClean(targetRepo)) {
            // §10.13 fallback: -fdx also removes gitignored files (e.g. build outputs
            // written by a partial apply that escaped the normal clean).
            git(targetRepo, "clean", "-fdx");
            if (!isPorcelainClean(targetRepo)) {
                throw new IllegalStateException(
                        "Target tree still dirty after git clean -fdx at sha " + sha +
                                " — manual inspection required (§10.13)");
            }
        }
    }

    // -------------------------------------------------------------------------
    // currentSha
    // -------------------------------------------------------------------------

    /**
     * Returns the current HEAD sha — the primitive for tracking baselineSha and
     * advancing it on keep. Loop logic itself is step 9.
     */
    public String currentSha(Path targetRepo) throws IOException, InterruptedException {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        return gitOutput(targetRepo, "rev-parse", "HEAD").trim();
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private List<EditDto> resolveEdits(Path targetRepo, ChangeDto change) throws Exception {
        if ("template".equals(change.kind())) {
            String id     = change.template();
            Map<String, String> params = change.params() != null ? change.params() : Map.of();
            // Registry enforces admission rule (§10.35); throws TemplateNotAdmittedException if unadmitted.
            return registry.expand(id, params, targetRepo);
        }
        if ("edits".equals(change.kind())) {
            if (change.edits() == null || change.edits().isEmpty()) {
                throw new IllegalArgumentException("kind=edits requires a non-empty edits list");
            }
            return change.edits();
        }
        throw new IllegalArgumentException("Unknown change kind: " + change.kind());
    }

    /**
     * Path confinement (§10.2): resolve + normalize against targetRepo; reject escapes
     * and absolute paths. Lexical normalize() alone misses symlink escapes, so the
     * nearest existing ancestor is also checked via toRealPath. Same policy readSource
     * uses in step 8.
     */
    private static void validatePath(Path targetRepo, String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Edit path must not be blank");
        }
        // Absolute paths are rejected outright — they bypass the confinement check.
        if (Path.of(rawPath).isAbsolute()) {
            throw new IllegalArgumentException("Edit path must be relative, got: " + rawPath);
        }
        Path repoRoot = targetRepo.toAbsolutePath().normalize();
        Path resolved = repoRoot.resolve(rawPath).normalize();
        if (!resolved.startsWith(repoRoot)) {
            // ../ escape — reject pre-apply, tree untouched.
            throw new IllegalArgumentException(
                    "Edit path escapes target root (§10.2): " + rawPath);
        }
        // §10.2, symlinks: walk up to the nearest existing ancestor (NOFOLLOW, so a
        // dangling symlink is found as itself). It must not be a symlink, and its
        // real path must stay under the repo root — everything below it doesn't
        // exist yet, so nothing else can divert the write.
        Path probe = resolved;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        Path anchor = (probe != null) ? probe : repoRoot;
        try {
            if (Files.isSymbolicLink(anchor) || !anchor.toRealPath().startsWith(repoRoot.toRealPath())) {
                throw new IllegalArgumentException(
                        "Edit path resolves outside target root via symlink (§10.2): " + rawPath);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve real path of " + rawPath, e);
        }
    }

    /** §10.3: per-file line cap ~400. Reject pre-apply; tree untouched; no commit. */
    private static void validateLineCap(EditDto edit) {
        long lines = edit.content().lines().count();
        if (lines > LINE_CAP) {
            throw new IllegalArgumentException(
                    "Edit for '" + edit.path() + "' exceeds " + LINE_CAP +
                            "-line cap (§10.3): " + lines + " lines");
        }
    }

    /**
     * Missing path → create parents + file; existing → replace whole; never delete (§10.12).
     * Write UTF-8, \n endings — clone has core.autocrlf=false (§10.33), so no phantom mods.
     */
    private static void writeFile(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        // Normalise to \n — the agent may emit \r\n on Windows; autocrlf=false means
        // git would see them as modifications on every status check.
        String normalised = content.replace("\r\n", "\n").replace("\r", "\n");
        Files.writeString(target, normalised, StandardCharsets.UTF_8);
    }

    private static int countLines(Path file) throws IOException {
        if (!Files.exists(file)) {
            return 0;
        }
        // readString is fine here — files are bounded by LINE_CAP (400 lines).
        return (int) Files.readString(file, StandardCharsets.UTF_8).lines().count();
    }

    /**
     * git add -A && git commit.
     * Spawns git.exe directly — no cmd /c (step-3 note: cmd /c adds a shell layer
     * that can swallow exit codes and mangle paths on Windows).
     * Captures stdout for the porcelain sha assertion.
     */
    private String gitCommit(Path targetRepo, String message) throws IOException, InterruptedException {
        git(targetRepo, "add", "-A");
        // --allow-empty is intentionally absent — a no-op change must not produce a commit.
        // But "nothing to commit" is a client error (identical content), not an
        // infrastructure failure — surface it as a parseable rejection, not a raw git exit.
        GitResult commit = execGit(targetRepo, "commit", "-m", message);
        if (commit.exitCode() != 0) {
            if (commit.output().contains("nothing to commit")) {
                throw new IllegalArgumentException(
                        "No-op change: content is byte-identical to HEAD, nothing committed " +
                                "(tree is clean; the iteration is wasted, not broken)");
            }
            throw new IllegalStateException(
                    "git commit failed (exit " + commit.exitCode() + "):\n" + commit.output());
        }
        return currentSha(targetRepo);
    }

    /**
     * Sanitize description to one line (§step-7 spec): strip everything after the first
     * newline so the commit message is a single subject line.
     */
    private static String sanitizeDescription(String description) {
        int nl = description.indexOf('\n');
        String line = nl >= 0 ? description.substring(0, nl) : description;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Description must contain non-whitespace text");
        }
        return trimmed;
    }

    private boolean isPorcelainClean(Path targetRepo) throws IOException, InterruptedException {
        String out = gitOutput(targetRepo, "status", "--porcelain");
        return out.trim().isEmpty();
    }

    /** Runs a git subcommand, asserting exit 0. Also the git access for verify gates. */
    public static void git(Path targetRepo, String... args) throws IOException, InterruptedException {
        gitOutput(targetRepo, args);
    }

    /**
     * Runs a git subcommand, asserting exit 0, and returns merged stdout+stderr.
     * Also the git access for verify gates.
     */
    public static String gitOutput(Path targetRepo, String... args) throws IOException, InterruptedException {
        GitResult result = execGit(targetRepo, args);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "git failed (exit " + result.exitCode() + "): " +
                            String.join(" ", args) + "\n" + result.output());
        }
        return result.output();
    }

    private record GitResult(int exitCode, String output) {}

    private static GitResult execGit(Path targetRepo, String... args) throws IOException, InterruptedException {
        // git.exe directly — no cmd /c (step-3 note).
        String[] cmd = new String[args.length + 1];
        cmd[0] = "git";
        System.arraycopy(args, 0, cmd, 1, args.length);

        Process process = new ProcessBuilder(cmd)
                .directory(targetRepo.toFile())
                .redirectErrorStream(true)
                .start();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        boolean finished = process.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("git timed out: " + String.join(" ", args));
        }
        return new GitResult(process.exitValue(), output);
    }
}
