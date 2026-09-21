package io.diag.runner.service;

import io.diag.runner.config.DockerPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Drives the per-run Docker Compose lifecycle: build → up → k6 → stop/harvest → down.
 * One instance per run-id; implements AutoCloseable so try-with-resources guarantees teardown.
 * Plain final class — no Spring, constructor injection only (scope §5).
 */
public final class TargetStack implements AutoCloseable {
    private final Path projectRoot;
    private final Path targetRepo;
    private final String runId;
    private final Path evidenceRoot;

    public TargetStack(Path projectRoot, Path targetRepo, String runId, Path evidenceRoot) {
        this.projectRoot  = Objects.requireNonNull(projectRoot, "projectRoot must not be null");
        this.targetRepo   = Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        this.runId        = Objects.requireNonNull(runId, "runId must not be null");
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot, "evidenceRoot must not be null");
    }

    /**
     * Creates the evidence subdir, builds the service image, and starts it.
     * {@code --wait} blocks until the healthcheck passes, so callers can immediately run k6.
     */
    public void up(String label, Path jarRel) throws IOException,InterruptedException {
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(jarRel, "jarRel must not be null");

        Files.createDirectories(evidenceRoot.resolve(runId).resolve(label));

        Map<String,String> env = baseEnv(label);
        // JAR_FILE is a Docker build arg — must use forward slashes regardless of host OS
        env.put("JAR_FILE", jarRel.toString().replace('\\', '/'));

        run(List.of("build","service"), env,10, TimeUnit.MINUTES);
        run(List.of("up","-d","--wait","service"), env,5,TimeUnit.MINUTES);

    }



    /** Tears down the stack and removes volumes. Safe to call after {@link #stopAndHarvest}. */
    public void down() throws IOException,InterruptedException{
        run(List.of("down","-v"), baseEnv(""),60,TimeUnit.SECONDS);
    }

    @Override
    public void close() throws Exception {
        try {
            down();
        }catch (Exception e){
            // best-effort teardown — swallowing is justified here; a close() must never throw
            // so the caller's try-with-resources can complete cleanup of other resources
            System.err.println("[TargetStack] down() failed during close(): " + e.getMessage());
        }
    }
    private void run(List<String> subcommand,Map<String,String> env,long timeout,TimeUnit unit) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(composePrefix());
        cmd.addAll(subcommand);

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .inheritIO();
        pb.environment().putAll(env);

        Process process = pb.start();
        boolean finished = process.waitFor(timeout,unit);
        if(!finished){
            process.destroyForcibly();
            throw new IllegalStateException("docker compose timed out: " + subcommand);
        }
        if(process.exitValue() != 0){
            throw new IllegalStateException("docker compose failed (exit " + process.exitValue() + "): " + subcommand);
        }
    }
    private List<String> composePrefix() {
        return List.of(
                "docker","compose",
                "-f",projectRoot.resolve("docker/envelope.yml").toString(),
                "-f",targetRepo.resolve("compose-service.yml").toString(),
                "-p",runId
        );
    }
    private Map<String, String> baseEnv(String label) {
        Map<String, String> env = new HashMap<>();
        env.put("REPO_DIR",      DockerPaths.toMount(targetRepo));
        env.put("JFR_SUBDIR",    label);
        env.put("EVIDENCE_DIR",  DockerPaths.toMount(evidenceRoot.resolve(runId)));
        env.put("BENCHMARKS_DIR", DockerPaths.toMount(projectRoot.resolve("benchmarks")));
        return env;
    }

    /**
     * Runs k6 via {@code compose run --rm}.
     * @param smoke true → 2 VUs / 5s smoke gate; false → committed 200-VU profile
     * @return k6 exit code — 99 means thresholds breached (measured FAIL), not an infrastructure error
     */
    public int runK6(String label, boolean smoke) throws IOException,InterruptedException {
        Objects.requireNonNull(label, "label must not be null");

        Map<String,String> env = baseEnv(label);
        env.put("K6_SUMMARY_OUT","/evidence/" + label + "/k6-summary.json");

        if(smoke) {
            env.put("K6_VUS",          "2");
            env.put("K6_DURATION",     "5s");
            env.put("K6_RAMP",         "1s");
            env.put("K6_ENTITY_COUNT", "3");
        }
        List<String> cmd = new ArrayList<>(composePrefix());
        cmd.addAll(List.of("run","--rm","k6"));

        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().putAll(env);

        Process process = pb.start();
        boolean finished = process.waitFor(10, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("k6 timed out for label: " + label);
        }
        return process.exitValue();
    }

    /**
     * Sends SIGTERM to the service (15s grace flushes JFR dumponexit), then asserts the
     * recording landed on disk. Use {@link #down()} afterward to remove the container.
     * @return path to the harvested profile.jfr
     */
    public Path stopAndHarvest(String label) throws IOException,InterruptedException{
        Objects.requireNonNull(label, "label must not be null");

        List<String> cmd = new ArrayList<>(composePrefix());
        cmd.addAll(List.of("stop","service"));

        ProcessBuilder pb = new ProcessBuilder(cmd).inheritIO();
        pb.environment().putAll(baseEnv(label));

        Process process = pb.start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new IllegalStateException("compose stop timed out for label: " + label);
        }

        Path jfr = evidenceRoot.resolve(runId).resolve(label).resolve("profile.jfr");
        if (!Files.exists(jfr) || Files.size(jfr) == 0) {
            throw new IllegalStateException("JFR not found or empty: " + jfr + " (see §10.15)");
        }
        return jfr;

    }
}
