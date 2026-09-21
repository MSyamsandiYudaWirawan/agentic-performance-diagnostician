package io.diag.runner.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Builds the target Maven project and picks the boot jar.
 * Plain final class — no Spring, no DI; called from TargetStack and the step gate test.
 */
public final class TargetBuilder {

    /**
     * Runs {@code mvn -B -q -DskipTests package} in {@code repoDir} and returns the boot jar
     * path relative to {@code repoDir} (the value JAR_FILE expects in the compose build arg).
     */
    public Path build(Path repoDir, Path logFile) throws IOException, InterruptedException{
        Objects.requireNonNull(repoDir, "repoDir must not be null");
        Objects.requireNonNull(logFile, "logFile must not be null");

        Process process = new ProcessBuilder("cmd","/c","mvn","-B","-q","-DskipTests","package")
                .directory(repoDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()))
                .start();

        boolean finished = process.waitFor(15, TimeUnit.MINUTES);
        if(!finished){
            process.destroyForcibly();
            throw new BuildFailedException("Maven build timed out after 15 minutes; log: " + logFile);
        }
        if(process.exitValue() != 0){
            throw new BuildFailedException("Maven build failed (exit " + process.exitValue() + "); log: "+logFile);
        }
        return pickBootJar(repoDir);

    }

    private Path pickBootJar(Path repoDir) throws IOException {
        Path targetDir = repoDir.resolve("target");

        // try-with-resources: Files.list holds a directory handle; leaked, it can
        // block later deletes (mvn clean on a future iteration) on Windows.
        try (Stream<Path> jars = Files.list(targetDir)) {
            return jars
                    .filter(p -> isBootJar(p.getFileName().toString()))
                    .max(Comparator.comparingLong(TargetBuilder::sizeOf))
                    .map(repoDir::relativize)
                    .orElseThrow(() -> new BuildFailedException("No boot jar found under " + targetDir));
        }
    }

    /** ".original" is Maven's pre-repackage backup — technically already excluded
     *  by the .jar suffix; kept visible as documentation of what we exclude. */
    private static boolean isBootJar(String name) {
        return name.endsWith(".jar")
                && !name.contains("sources")
                && !name.contains("javadoc")
                && !name.endsWith(".original");
    }

    private static long sizeOf(Path candidate) {
        try {
            return Files.size(candidate);
        } catch (IOException e) {
            // an unreadable jar must not silently lose the size contest (TIGERSTYLE §2)
            throw new UncheckedIOException("Cannot size candidate jar: " + candidate, e);
        }
    }
}
