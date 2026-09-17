package io.diag.runner;

import java.io.IOException;

/**
 * Post-run envelope assertion (scope §10.28), as an injected seam so
 * BenchmarkRunner stays unit-testable: the real implementation talks to
 * Docker, tests supply a mock. A tampered envelope invalidates the run —
 * the result is neither PASS nor FAIL.
 */
@FunctionalInterface
public interface EnvelopeGuard {

    /**
     * @param containerName the target container to inspect
     * @throws IllegalStateException when the container's resource budget differs
     *                               from the envelope, or the budget cannot be read
     * @throws IOException           when talking to Docker fails
     * @throws InterruptedException  when the calling thread is interrupted
     */
    void assertBudget(String containerName) throws IOException, InterruptedException;
}
