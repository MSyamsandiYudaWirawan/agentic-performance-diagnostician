package io.diag.eval.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Service contract for generating self-contained offline HTML reports (§10.26, TigerStyle compliant).
 * Reports contain zero external CDN dependencies, embedded CSS, inline SVG charts,
 * timeline tables, and trajectory event logs.
 */
public interface HtmlReportGenerator {

    /**
     * Generates a self-contained offline HTML report for a single run.
     *
     * @param runId identifier of the run
     * @return HTML report document string
     */
    String generateRunReport(String runId);

    /**
     * Generates and writes a single-run HTML report to a target file.
     *
     * @param runId identifier of the run
     * @param destinationFile destination file path
     * @return the written destination path
     * @throws IOException if writing fails
     */
    Path exportRunReport(String runId, Path destinationFile) throws IOException;

    /**
     * Generates a self-contained offline HTML report for a matrix of runs.
     *
     * @param runIds list of run identifiers
     * @return HTML report document string
     */
    String generateMatrixReport(List<String> runIds);
}
