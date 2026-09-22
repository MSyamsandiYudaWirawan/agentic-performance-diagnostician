package io.diag.runner.service;

import io.diag.evidence.dto.EditDto;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * A fix template expands into a list of full-file edits (scope §10.23).
 * Expansion is read-modify-write: the template reads the current target file,
 * transforms it, and emits the full file as one EditDto — the edit format stays locked.
 * The same gates as manual edits apply; no bypass (§10.35).
 */
public interface FixTemplate {
    String id();
    List<EditDto> expand(Path targetRepo, Map<String, String> params) throws Exception;
}
