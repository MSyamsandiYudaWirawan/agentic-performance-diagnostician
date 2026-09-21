/**
 * Target runner (scope §5): deterministic pipeline over the target working
 * tree — build ({@code cmd /c mvn}), docker compose lifecycle ({@code -p
 * <run-id>}), smoke gate, k6 execution + LoadReport, always-on JFR capture
 * (per-run subdirs, never {@code disk=true}), envelope assertion, seeding.
 *
 * <p>No LLM in this module. Drives docker/git/mvn via {@code ProcessBuilder};
 * bind-mount paths via {@code DockerPaths.toMount} (backslashes to
 * {@code C:/...} forward slashes — scope §10.10).
 *
 * <p>Layout: {@code config} (run/environment configuration — thresholds,
 * path adaptation) and {@code service} (the pipeline doers — build, compose
 * lifecycle, benchmark, JFR capture + analysis).
 */
package io.diag.runner;
