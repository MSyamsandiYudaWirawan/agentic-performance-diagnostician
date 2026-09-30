package io.diag.evidence.service;

import io.diag.evidence.entity.Target;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Target Registry contract (spec step 10 M0, scope §3, §10.23, §10.24).
 * Manages target metadata, canonical S1-S4 definitions, and profile hash computation.
 */
public interface TargetRegistry {

    Target register(Target target);

    Optional<Target> findById(String id);

    List<Target> findAll();

    String computeProfileHash(String targetTreeSha, Path k6ScriptPath, Path envelopePath);

    String computeProfileHash(String targetTreeSha, byte[] k6ScriptBytes, byte[] envelopeBytes);

    List<Target> getCanonicalTargets();

    void initCanonicalTargets();
}
