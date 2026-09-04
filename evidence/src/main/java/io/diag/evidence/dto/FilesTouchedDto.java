package io.diag.evidence.dto;

/**
 * One touched file in an iteration's change-transparency audit trail
 * (scope §10.28). Persisted as iteration.files_touched — a JSON array,
 * one entry per file.
 */
public record FilesTouchedDto(
        String path,
        int linesBefore,
        int linesAfter
) {
}
