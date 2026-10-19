package io.diag.agent.memory;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.AbstractEmbeddingModel;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, 100% offline embedding model for unit testing with SimpleVectorStore.
 * Maps domain symptom keywords (lock/H5, pool/H2, GC/H3, CPU/H4) into orthogonal feature axes
 * plus a small positive baseline component so empty queries ("") yield valid non-NaN unit vectors.
 */
public class FakeEmbeddingModel extends AbstractEmbeddingModel {

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions();
        List<Embedding> embeddings = new ArrayList<>(instructions.size());
        for (int i = 0; i < instructions.size(); i++) {
            embeddings.add(new Embedding(computeVector(instructions.get(i)), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return computeVector(document != null ? document.getText() : "");
    }

    private static float[] computeVector(String raw) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.ROOT);
        float[] v = new float[8];

        // Axis 0: Lock contention / ClassLoader / JarFile / H5
        if (containsAny(text, "lock", "javamonitorenter", "jarfile", "urlclasspath", "classloader", "synchronized", "monitor")) {
            v[0] += 5.0f;
        }
        // Axis 1: Connection pool / Hikari / ThreadPark / I/O / H2
        if (containsAny(text, "hikari", "pool", "threadpark", "connection", "jdbc", "socket")) {
            v[1] += 5.0f;
        }
        // Axis 2: GC starvation / allocation / heap / H3
        if (containsAny(text, "gc", "gcphasepause", "starvation", "heap", "allocation", "pause")) {
            v[2] += 5.0f;
        }
        // Axis 3: CPU hotspots / ExecutionSample / H4
        if (containsAny(text, "cpu", "executionsample", "regex", "serialization")) {
            v[3] += 5.0f;
        }

        // Small positive baseline on Axis 7 so empty query ("") has non-zero norm and positive cosine (> 0.0)
        v[7] = 0.2f;

        // L2 normalize
        double norm = 0.0;
        for (float x : v) {
            norm += (double) x * x;
        }
        norm = Math.sqrt(norm);
        if (norm > 0.0) {
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
