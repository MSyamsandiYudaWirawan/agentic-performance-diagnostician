package io.diag.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.Objects;

@EnableConfigurationProperties(MemoryConfig.class)
@ConfigurationProperties(prefix = "diag.memory")
public record MemoryConfig(
        boolean enabled,
        boolean evaluationMode,
        String embeddingModel,          // e.g. "text-embedding-3-small" or "nomic-embed-text"
        double similarityThreshold,     // calibrated per model: 0.70 (OpenAI) / 0.55 (local models)
        int topK,                       // maximum diverse memories to inject (default: 3)
        int schemaVersion               // document format version (default: 1)
) {
    public MemoryConfig {
        Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        if (embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel must not be blank");
        }
        if (similarityThreshold <= 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be in (0.0, 1.0], got: " + similarityThreshold);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got: " + topK);
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be > 0, got: " + schemaVersion);
        }
    }

    /**
     * Standard defaults for tests and baseline operation.
     */
    public static MemoryConfig defaults() {
        return new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1);
    }
}