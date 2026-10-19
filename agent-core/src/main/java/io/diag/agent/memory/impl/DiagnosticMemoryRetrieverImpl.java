package io.diag.agent.memory.impl;

import io.diag.agent.memory.DiagnosticMemoryRetriever;
import io.diag.agent.memory.MemoryConfig;
import io.diag.agent.memory.MemoryQuery;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import java.util.*;

public class DiagnosticMemoryRetrieverImpl implements DiagnosticMemoryRetriever {

    private final MemoryConfig memoryConfig;
    private final VectorStore vectorStore;

    public DiagnosticMemoryRetrieverImpl(MemoryConfig memoryConfig, VectorStore vectorStore) {
        this.memoryConfig = Objects.requireNonNull(memoryConfig, "MemoryConfig must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "VectorStore must not be null");
    }

    @Override
    public List<Document> retrieve(MemoryQuery query, String currentTargetId) {


        FilterExpressionBuilder b = new FilterExpressionBuilder();


        FilterExpressionBuilder.Op filter = b.eq("embeddingModel", memoryConfig.embeddingModel());
        filter = b.and(filter, b.eq("schemaVersion", memoryConfig.schemaVersion()));

        if (memoryConfig.evaluationMode()) {
            filter = b.and(filter, b.ne("targetId", currentTargetId));
        }
        if (!isNullOrBlank(query.category())) {
            filter = b.and(filter, b.eq("category", query.category()));
        }
        if (!isNullOrBlank(query.template())) {
            filter = b.and(filter, b.eq("template", query.template()));
        }
        if (!isNullOrBlank(query.outcome())) {
            filter = b.and(filter, b.eq("outcome", query.outcome()));
        }


        SearchRequest request = SearchRequest.builder()
                .query(!isNullOrBlank(query.symptomQuery()) ? query.symptomQuery() : "")
                .similarityThreshold(!isNullOrBlank(query.symptomQuery()) ? memoryConfig.similarityThreshold() : 0.0)
                .filterExpression(filter.build())
                .topK(memoryConfig.topK() * 3)
                .build();

        List<Document> documents = vectorStore.similaritySearch(request);
        Set<String> seen = new HashSet<>();

        List<Document> result = new ArrayList<>();
        for (Document d : documents) {
            String key = d.getMetadata().get("targetId") + ":" + d.getMetadata().get("template");
            if (seen.contains(key)) {
                continue;
            }
            if (result.size() >= memoryConfig.topK()) {
                break;
            }
            seen.add(key);
            result.add(d);
        }
        return result;
    }

    private boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }
}
