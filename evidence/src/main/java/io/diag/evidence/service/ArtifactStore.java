package io.diag.evidence.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Copies raw artifacts (k6 summary JSON, .jfr recordings, build logs) under
 * evidence/artifacts/&lt;run-id&gt;/&lt;label&gt;/ and returns the stored path +
 * sha256. The DB stores paths and hashes, never blobs (scope §10.18).
 */
@Component
public class ArtifactStore {

    private final Path root;

    public ArtifactStore(@Value("${evidence.artifacts-root:evidence/artifacts}") Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public record StoredArtifact(Path path, String sha256) {
    }

    public StoredArtifact store(String runId, String label, Path source) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(source, "source must not be null");

        Path dir = root.resolve(runId).resolve(label);
        Path target;
        try {
            Files.createDirectories(dir);
            target = dir.resolve(source.getFileName());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("failed to store artifact " + source + " for run " + runId, e);
        }
        return new StoredArtifact(target, sha256(target));
    }

    private static String sha256(Path file) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("failed to hash artifact " + file, e);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
