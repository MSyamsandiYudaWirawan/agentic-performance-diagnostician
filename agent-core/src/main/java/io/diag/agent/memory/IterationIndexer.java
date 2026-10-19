package io.diag.agent.memory;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;

public interface IterationIndexer {

    /**
     * Ingests a completed iteration into the VectorStore.
     *
     * Ingestion rules:
     * 1. Only indexes iterations where outcome is KEPT or REVERTED.
     * 2. Skips ALL WASTED iterations unconditionally. Wasted iterations (malformed decision,
     *    compilation error, or smoke timeout) lack benchmark-verified evidence and must not
     *    pollute the memory store.
     * 3. Uses SymptomSummarizer and IterationDocumentFactory to generate the Document.
     * 4. Appends to the underlying VectorStore.
     *
     * Ingestion Trigger Hook:
     * Called directly inside AgentLoop.java after KeepRule evaluates the cycle and
     * evidenceService.createIteration(...) writes the row to Postgres.
     */
    void index(Iteration iteration, Run run, LoadReportDto loadReportDto, JfrReportDto jfrReportDto);
}
