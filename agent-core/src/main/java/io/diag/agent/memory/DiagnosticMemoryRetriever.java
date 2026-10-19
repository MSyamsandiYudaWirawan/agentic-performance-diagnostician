package io.diag.agent.memory;

import org.springframework.ai.document.Document;

import java.util.List;

public interface DiagnosticMemoryRetriever {

    /**
     * Retrieves diverse past diagnostic experiences matching symptoms and/or metadata filters.
     *
     * Operational parameters (topK, similarityThreshold, evaluationMode, embeddingModel)
     * are read directly from the injected MemoryConfig and executed directly via
     * VectorStore.similaritySearch(SearchRequest).
     *
     * Execution Semantics (Guard 4):
     * 1. Hybrid / Semantic (query.hasSymptomQuery() == true):
     *    SearchRequest.builder().query(query.symptomQuery()).similarityThreshold(config.similarityThreshold()).filterExpression(filter).topK(config.topK()).build()
     * 2. Pure Metadata (query.hasSymptomQuery() == false):
     *    SearchRequest.builder().query("").similarityThreshold(0.0).filterExpression(filter).topK(config.topK()).build()
     *    Note: MemoryConfig enforces similarityThreshold in (0.0, 1.0] for semantic queries to prevent misconfiguration.
     *    Guard 4 passes 0.0 directly to SearchRequest at runtime for pure metadata audits, ensuring uniform behavior
     *    across SimpleVectorStore and PgVectorStore without bifurcating code.
     *
     * @param query Structured query containing optional semantic text and metadata filters
     * @param currentTargetId The target currently being diagnosed (for eval isolation)
     * @return List of deduplicated, filtered memory documents (size <= config.topK())
     */
    List<Document> retrieve(MemoryQuery query, String currentTargetId);
}
