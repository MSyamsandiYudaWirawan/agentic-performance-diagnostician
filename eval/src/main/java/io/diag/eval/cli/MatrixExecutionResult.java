package io.diag.eval.cli;

import io.diag.eval.model.DiagnosticTriage;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RegressionRow;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Immutable value record summarizing the full execution of a diagnostic matrix (§6, Step 12 M0).
 */
public record MatrixExecutionResult(
        MatrixScoreReport scoreReport,
        List<RegressionRow> regressionTable,
        List<DiagnosticTriage> triages,
        Path matrixReportHtmlPath,
        Path regressionTableMdPath,
        List<Path> runReportPaths
) {
    public MatrixExecutionResult {
        Objects.requireNonNull(scoreReport, "scoreReport must not be null");
        Objects.requireNonNull(regressionTable, "regressionTable must not be null");
        Objects.requireNonNull(triages, "triages must not be null");
        Objects.requireNonNull(matrixReportHtmlPath, "matrixReportHtmlPath must not be null");
        Objects.requireNonNull(regressionTableMdPath, "regressionTableMdPath must not be null");
        Objects.requireNonNull(runReportPaths, "runReportPaths must not be null");
    }

    public MatrixExecutionResult(
            MatrixScoreReport scoreReport,
            List<RegressionRow> regressionTable,
            Path matrixReportHtmlPath,
            Path regressionTableMdPath,
            List<Path> runReportPaths
    ) {
        this(scoreReport, regressionTable, List.of(), matrixReportHtmlPath, regressionTableMdPath, runReportPaths);
    }

    /**
     * Checks if the matrix execution passed the v1.0 success criteria threshold (§6).
     */
    public boolean isSuccess() {
        return scoreReport.passesV1Threshold();
    }
}
