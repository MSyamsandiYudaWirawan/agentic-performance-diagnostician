package io.diag.runner.config;

import io.diag.evidence.dto.FilesTouchedList;

/**
 * Returned by ChangeApplier.apply — the commit sha, touched-file audit trail,
 * and the code-touch flag (scope §10.28, §10.31).
 */
public record ChangeResult(
        String commitSha,
        FilesTouchedList filesTouched,
        boolean javaTouched
) {
}
