package io.diag.runner;

import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.FixTemplateRegistry;
import io.diag.runner.service.SeededTarget;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Step 10 Milestone 1 Verify Gate (SeededTargetTest).
 * Tests seeding lifecycle, git commit isolation, and clean revert round-tripping.
 */
public class SeededTargetTest {

    private static final Path PETCLINIC_SOURCE = Path.of("C:/study/agentic-performance-diagnostician/targets/spring-petclinic");

    private Path scratchRepo;
    private Path tempPatch;
    private SeededTarget seededTarget;
    private ChangeApplier changeApplier;
    private String pristineSha;

    @BeforeEach
    void setUp() throws Exception {
        scratchRepo = Files.createTempDirectory("seeded-target-test-");

        // Clone scratch repo with core.autocrlf=false pinned at clone time (§10.33)
        git(Path.of("."), "clone", "-c", "core.autocrlf=false",
                PETCLINIC_SOURCE.toString(), scratchRepo.toString());

        changeApplier = new ChangeApplier(new FixTemplateRegistry());
        seededTarget = new SeededTarget(changeApplier);
        pristineSha = changeApplier.currentSha(scratchRepo);

        // Create a valid sample git patch to apply on application.properties
        tempPatch = Files.createTempFile("test-seed-", ".patch");
        String patchContent = """
                diff --git a/src/main/resources/application.properties b/src/main/resources/application.properties
                --- a/src/main/resources/application.properties
                +++ b/src/main/resources/application.properties
                @@ -1,3 +1,4 @@
                +# test seed marker
                 # database init, supports mysql too
                 database=h2
                 spring.sql.init.schema-locations=classpath*:db/${database}/schema.sql
                """;
        Files.writeString(tempPatch, patchContent);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempPatch != null) {
            Files.deleteIfExists(tempPatch);
        }
        if (scratchRepo != null && Files.exists(scratchRepo)) {
            try (Stream<Path> walk = Files.walk(scratchRepo)) {
                walk.sorted(Comparator.reverseOrder())
                        .forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                System.err.println("[SeededTargetTest] cleanup failed: " + e.getMessage());
            }
        }
    }

    @Test
    void seedPristine_leavesRepoAtPristineShaAndClean() throws Exception {
        String sha = seededTarget.seed(scratchRepo, pristineSha, null, "S1", "Stock PetClinic");
        assertThat(sha).isEqualTo(pristineSha);

        String status = gitOutput(scratchRepo, "status", "--porcelain");
        assertThat(status.trim()).isEmpty();
    }

    @Test
    void seedWithPatch_commitsNewSha_andRoundTripsCleanly() throws Exception {
        // Apply seed
        String seededSha = seededTarget.seed(scratchRepo, pristineSha, tempPatch, "S_TEST", "Test Seed");
        assertThat(seededSha).isNotEqualTo(pristineSha);

        // Verify clean working tree after seeding
        String statusAfterSeed = gitOutput(scratchRepo, "status", "--porcelain");
        assertThat(statusAfterSeed.trim()).isEmpty();

        // Check content has the seed
        String propertiesContent = Files.readString(scratchRepo.resolve("src/main/resources/application.properties"));
        assertThat(propertiesContent).contains("# test seed marker");

        // Reset to seeded baseline
        seededTarget.reset(scratchRepo, seededSha);
        assertThat(changeApplier.currentSha(scratchRepo)).isEqualTo(seededSha);
        assertThat(gitOutput(scratchRepo, "status", "--porcelain").trim()).isEmpty();

        // Reset to pristine base
        seededTarget.resetToPristine(scratchRepo, pristineSha);
        assertThat(changeApplier.currentSha(scratchRepo)).isEqualTo(pristineSha);
        assertThat(gitOutput(scratchRepo, "status", "--porcelain").trim()).isEmpty();

        String revertedContent = Files.readString(scratchRepo.resolve("src/main/resources/application.properties"));
        assertThat(revertedContent).doesNotContain("# test seed marker");
    }

    @Test
    void missingPatchFile_throwsIllegalArgument() {
        Path nonExistent = Path.of("non-existent-seed.patch");
        assertThatThrownBy(() -> seededTarget.seed(scratchRepo, pristineSha, nonExistent, "S_ERR", "desc"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        if (p.exitValue() != 0) {
            String output = new String(p.getInputStream().readAllBytes());
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
    }

    private static String gitOutput(Path dir, String... args) throws Exception {
        List<String> command = Stream.concat(Stream.of("git"), Stream.of(args)).toList();
        Process p = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        boolean finished = p.waitFor(30, TimeUnit.SECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new IllegalStateException("git command timed out: " + String.join(" ", command));
        }
        String output = new String(p.getInputStream().readAllBytes());
        if (p.exitValue() != 0) {
            throw new IllegalStateException("git command failed (" + p.exitValue() + "): " + output);
        }
        return output;
    }
}
