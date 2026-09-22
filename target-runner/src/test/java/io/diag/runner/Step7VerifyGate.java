package io.diag.runner;

import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.EditDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.runner.config.ChangeResult;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.CodeTouchGate;
import io.diag.runner.service.FixTemplateRegistry;
import io.diag.runner.service.TemplateNotAdmittedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static io.diag.runner.service.ChangeApplier.git;
import static io.diag.runner.service.ChangeApplier.gitOutput;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Step 7 verify gate (build-steps step 7): safe mutations, revert, templates.
 * No Docker, no DB. Uses a scratch clone of targets/spring-petclinic.
 * <p>
 * Opt-in:
 * mvn -pl target-runner -am test "-Dstep7.gate=true" "-Dtest=Step7VerifyGate" "-Dsurefire.failIfNoSpecifiedTests=false"
 */
class Step7VerifyGate {

    static final Path PETCLINIC_SOURCE = Path.of("C:/study/agentic-performance-diagnostician/targets/spring-petclinic");
    static final String PROPERTIES = "src/main/resources/application.properties";

    private Path scratchRepo;
    private ChangeApplier applier;
    private FixTemplateRegistry registry;
    private CodeTouchGate codeTouchGate;

    @BeforeEach
    void cloneScratch() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        scratchRepo = Files.createTempDirectory("step7-gate-");
        // Local clone — fast, zero risk to the real tree. autocrlf=false must be
        // pinned AT CLONE TIME (§10.33): this machine's system-wide autocrlf=true
        // makes a default clone check out CRLF; switching the config off afterwards
        // leaves ~68 files phantom-modified, and the first `git add -A` would commit
        // the whole tree as line-ending noise.
        git(Path.of("."), "clone", "-c", "core.autocrlf=false",
                PETCLINIC_SOURCE.toString(), scratchRepo.toString());

        registry = new FixTemplateRegistry();
        applier = new ChangeApplier(registry);
        codeTouchGate = new CodeTouchGate();
    }

    @AfterEach
    void deleteScratch() throws IOException {
        if (scratchRepo == null || !Files.exists(scratchRepo)) return;
        // Best-effort recursive delete — a leftover temp dir is not a test failure.
        try (var walk = Files.walk(scratchRepo)) {
            walk.sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        } catch (IOException e) {
            System.out.println("[step7-gate] cleanup failed: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // 1. Apply edits — commit exists, linesBefore/After correct, HEAD advanced
    // -------------------------------------------------------------------------

    @Test
    void applyEditsCommitsAndAdvancesHead() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String baselineSha = applier.currentSha(scratchRepo);

        Path propsFile = scratchRepo.resolve(PROPERTIES);
        int linesBefore = (int) Files.readString(propsFile).lines().count();

        String newContent = Files.readString(propsFile) + "\n# step7-gate marker\n";
        ChangeDto change = new ChangeDto("edits",
                List.of(new EditDto(PROPERTIES, newContent),
                        new EditDto("step7-new-file.txt", "created by step7 gate\n")),
                null, null);

        ChangeResult result = applier.apply(scratchRepo, change, "step7: apply edits test");

        assertNotEquals(baselineSha, result.commitSha(), "HEAD must advance");
        assertEquals(result.commitSha(), applier.currentSha(scratchRepo));
        assertFalse(result.javaTouched(), "no .java files touched");
        // The two .java-predicates (audit flag §10.28, gate §10.31) must agree:
        assertFalse(codeTouchGate.isJavaTouched(result.filesTouched()));

        FilesTouchedList touched = result.filesTouched();
        assertEquals(2, touched.files().size());

        var propsTouched = touched.files().stream()
                .filter(f -> f.path().contains("application.properties"))
                .findFirst().orElseThrow();
        assertEquals(linesBefore, propsTouched.linesBefore());
        assertTrue(propsTouched.linesAfter() > linesBefore, "linesAfter must grow");

        var newFileTouched = touched.files().stream()
                .filter(f -> f.path().equals("step7-new-file.txt"))
                .findFirst().orElseThrow();
        assertEquals(0, newFileTouched.linesBefore(), "created file has 0 linesBefore");

        // Commit message must match description
        String log = gitOutput(scratchRepo, "log", "-1", "--format=%s");
        assertEquals("step7: apply edits test", log.trim());
    }

    // -------------------------------------------------------------------------
    // 2. 400-line cap rejected — tree unchanged, no new commit
    // -------------------------------------------------------------------------

    @Test
    void lineCap400Rejected() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String beforeSha = applier.currentSha(scratchRepo);
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 401; i++) big.append("line ").append(i).append("\n");

        ChangeDto change = new ChangeDto("edits",
                List.of(new EditDto("too-big.txt", big.toString())),
                null, null);

        assertThrows(IllegalArgumentException.class,
                () -> applier.apply(scratchRepo, change, "should be rejected"));

        assertEquals(beforeSha, applier.currentSha(scratchRepo), "HEAD must not advance");
        assertFalse(Files.exists(scratchRepo.resolve("too-big.txt")), "file must not be created");
    }

    // -------------------------------------------------------------------------
    // 3. ../ escape and absolute paths rejected pre-apply (§10.2)
    // -------------------------------------------------------------------------

    @Test
    void pathEscapeRejected() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String beforeSha = applier.currentSha(scratchRepo);

        ChangeDto escape = new ChangeDto("edits",
                List.of(new EditDto("../evil.txt", "outside the repo\n")),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> applier.apply(scratchRepo, escape, "should be rejected"));
        assertFalse(Files.exists(scratchRepo.getParent().resolve("evil.txt")),
                "file must not be created outside the repo");

        ChangeDto absolute = new ChangeDto("edits",
                List.of(new EditDto(scratchRepo.getParent().resolve("evil-abs.txt").toString(), "absolute\n")),
                null, null);
        assertThrows(IllegalArgumentException.class,
                () -> applier.apply(scratchRepo, absolute, "should be rejected"));
        assertFalse(Files.exists(scratchRepo.getParent().resolve("evil-abs.txt")));

        assertEquals(beforeSha, applier.currentSha(scratchRepo), "HEAD must not advance");
    }

    // -------------------------------------------------------------------------
    // 4. Revert round-trip — contents and git log match expectations (§10.13)
    // -------------------------------------------------------------------------

    @Test
    void revertRoundTripRestoresContentsAndLog() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String baselineSha = applier.currentSha(scratchRepo);
        String baselineSubject = gitOutput(scratchRepo, "log", "-1", "--format=%s").trim();

        Path propsFile = scratchRepo.resolve(PROPERTIES);
        String originalProps = Files.readString(propsFile);

        ChangeDto change = new ChangeDto("edits",
                List.of(new EditDto(PROPERTIES, originalProps + "\n# step7-gate revert marker\n"),
                        new EditDto("step7-revert-file.txt", "created to be reverted\n")),
                null, null);
        applier.apply(scratchRepo, change, "step7: revert round-trip test");
        assertNotEquals(baselineSha, applier.currentSha(scratchRepo));

        applier.revertTo(scratchRepo, baselineSha);

        assertEquals(baselineSha, applier.currentSha(scratchRepo), "HEAD must return to baseline");
        assertEquals(originalProps, Files.readString(propsFile), "file contents must be restored");
        assertFalse(Files.exists(scratchRepo.resolve("step7-revert-file.txt")),
                "created file must be gone");
        assertEquals("", gitOutput(scratchRepo, "status", "--porcelain").trim(),
                "porcelain must be empty after revert");
        assertEquals(baselineSubject, gitOutput(scratchRepo, "log", "-1", "--format=%s").trim(),
                "HEAD subject must be the baseline commit again");
        assertFalse(gitOutput(scratchRepo, "log", "--format=%s").contains("step7: revert round-trip test"),
                "applied commit must be gone from the log");
    }

    // -------------------------------------------------------------------------
    // 5. Admitted template: jar-unpack applies and reverts cleanly
    // -------------------------------------------------------------------------

    @Test
    void jarUnpackTemplateAppliesAndReverts() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String baselineSha = applier.currentSha(scratchRepo);
        Path dockerfile = scratchRepo.resolve("Dockerfile.target");
        String originalDockerfile = Files.readString(dockerfile);

        ChangeResult result = applier.apply(scratchRepo,
                new ChangeDto("template", null, "jar-unpack", null),
                "step7: jar-unpack template test");

        assertNotEquals(baselineSha, result.commitSha(), "HEAD must advance");
        assertFalse(result.javaTouched());
        String applied = Files.readString(dockerfile);
        assertTrue(applied.contains("-Djarmode=tools") && applied.contains("extract --destination unpacked"),
                "extract RUN line must be present");
        assertTrue(applied.contains("/app/unpacked/app.jar"), "entrypoint must run the unpacked jar");
        // Committed, not just on disk:
        String committed = gitOutput(scratchRepo, "show", "HEAD:Dockerfile.target");
        assertTrue(committed.contains("extract --destination unpacked"), "change must be committed");

        applier.revertTo(scratchRepo, baselineSha);
        assertEquals(originalDockerfile, Files.readString(dockerfile), "Dockerfile must be restored");
        assertEquals("", gitOutput(scratchRepo, "status", "--porcelain").trim());
    }

    // -------------------------------------------------------------------------
    // 6. Gate requirement: hikari-pool-size expansion produces a valid committed
    //    diff and reverts cleanly (candidate — expandUnchecked, §10.35)
    // -------------------------------------------------------------------------

    @Test
    void hikariExpansionProducesValidCommittedDiffAndReverts() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String baselineSha = applier.currentSha(scratchRepo);
        Path propsFile = scratchRepo.resolve(PROPERTIES);
        String originalProps = Files.readString(propsFile);

        List<EditDto> edits = registry.expandUnchecked(
                "hikari-pool-size", Map.of("poolSize", "10"), scratchRepo);
        applier.apply(scratchRepo, new ChangeDto("edits", edits, null, null),
                "step7: hikari-pool-size expansion test");

        String applied = Files.readString(propsFile);
        long occurrences = applied.lines()
                .filter(l -> l.equals("spring.datasource.hikari.maximumPoolSize=10"))
                .count();
        assertEquals(1, occurrences, "exactly one pool-size property line, properly separated");

        // The committed diff touches only the properties file:
        String changedFiles = gitOutput(scratchRepo, "diff", "--name-only", baselineSha, "HEAD").trim();
        assertEquals(PROPERTIES, changedFiles, "diff must touch only application.properties");

        applier.revertTo(scratchRepo, baselineSha);
        assertEquals(originalProps, Files.readString(propsFile), "properties must be restored byte-for-byte");
        assertEquals("", gitOutput(scratchRepo, "status", "--porcelain").trim());
    }

    // -------------------------------------------------------------------------
    // 7. No-op change (identical content) — rejected, no commit, tree clean
    // -------------------------------------------------------------------------

    @Test
    void noOpApplyRejectedWithoutCommit() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        Path propsFile = scratchRepo.resolve(PROPERTIES);
        ChangeDto change = new ChangeDto("edits",
                List.of(new EditDto(PROPERTIES, Files.readString(propsFile) + "\n# step7 noop marker\n")),
                null, null);
        String firstSha = applier.apply(scratchRepo, change, "step7: noop test first apply").commitSha();

        // Same content again → parseable rejection, HEAD unchanged, tree clean.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> applier.apply(scratchRepo, change, "step7: noop test second apply"));
        assertTrue(ex.getMessage().contains("No-op change"), "rejection must be the no-op one");
        assertEquals(firstSha, applier.currentSha(scratchRepo), "HEAD must stay at the first commit");
        assertEquals("", gitOutput(scratchRepo, "status", "--porcelain").trim(),
                "tree must be clean — identical content staged nothing");
    }

    // -------------------------------------------------------------------------
    // 8. Admission gate: unadmitted candidate rejected, unknown id rejected
    //    (§10.35) — both pre-commit, HEAD unchanged
    // -------------------------------------------------------------------------

    @Test
    void unadmittedAndUnknownTemplatesRejected() throws Exception {
        if (!Boolean.getBoolean("step7.gate")) return;

        String beforeSha = applier.currentSha(scratchRepo);

        // Known id but not admitted → §10.35 rejection (not "unknown"):
        assertThrows(TemplateNotAdmittedException.class, () -> applier.apply(scratchRepo,
                new ChangeDto("template", null, "hikari-pool-size", Map.of("poolSize", "10")),
                "should be rejected"));

        // Hallucinated id → unknown-template error:
        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> applier.apply(scratchRepo,
                        new ChangeDto("template", null, "jar-unpackk", null),
                        "should be rejected"));
        assertTrue(unknown.getMessage().contains("Unknown template id"),
                "hallucinated id must be reported as unknown, not unadmitted");

        assertEquals(beforeSha, applier.currentSha(scratchRepo), "HEAD must not advance");
        assertFalse(registry.isAdmitted("hikari-pool-size"));
        assertTrue(registry.isAdmitted("jar-unpack"));
    }
}
