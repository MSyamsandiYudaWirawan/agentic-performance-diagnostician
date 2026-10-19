package io.diag.agent.memory.impl;

import io.diag.agent.memory.IterationDocumentFactory;
import io.diag.agent.memory.IterationIndexer;
import io.diag.agent.memory.MemoryConfig;
import io.diag.agent.memory.SymptomSummarizer;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public class IterationIndexerImpl implements IterationIndexer {

    private static final Logger log = LoggerFactory.getLogger(IterationIndexerImpl.class);
    private static final Set<String> INDEXABLE_OUTCOMES = Set.of("KEPT", "REVERTED");

    private final MemoryConfig memoryConfig;
    private final SymptomSummarizer symptomSummarizer;
    private final IterationDocumentFactory documentFactory;
    private final VectorStore vectorStore;

    public IterationIndexerImpl(MemoryConfig memoryConfig, SymptomSummarizer symptomSummarizer, IterationDocumentFactory documentFactory, VectorStore vectorStore) {
        this.memoryConfig = Objects.requireNonNull(memoryConfig, "MemoryConfig must not be null");
        this.symptomSummarizer = Objects.requireNonNull(symptomSummarizer, "SymptomSummarizer must not be null");
        this.documentFactory = Objects.requireNonNull(documentFactory, "IterationDocumentFactory must not be null");
        this.vectorStore = Objects.requireNonNull(vectorStore, "VectorStore must not be null");
    }

    @Override
    public void index(Iteration iteration, Run run, LoadReportDto loadReportDto, JfrReportDto jfrReportDto) {
        if (!memoryConfig.enabled()) {
            return;
        }
        if (iteration == null || run == null || loadReportDto == null || jfrReportDto == null) {
            return;
        }
        String outcome = iteration.getOutcome();
        if (outcome == null || !INDEXABLE_OUTCOMES.contains(outcome.trim().toUpperCase())) {
            // Skip all WASTED iterations unconditionally
            return;
        }

        try {
            String symptom = symptomSummarizer.summarize(loadReportDto, jfrReportDto);
            Document doc = documentFactory.toDocument(iteration, run, symptom);
            vectorStore.add(List.of(doc));
        } catch (Exception e) {
            log.warn("Failed to index iteration {} for run {}: {}", iteration.getN(), run.getId(), e.getMessage());
        }

    }
}
