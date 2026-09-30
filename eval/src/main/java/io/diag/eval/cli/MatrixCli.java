package io.diag.eval.cli;

import io.diag.eval.model.DiagnosticTriage;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RegressionRow;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.DiagnosticTriageService;
import io.diag.eval.service.HtmlReportGenerator;
import io.diag.eval.service.MatrixRunner;
import io.diag.eval.service.RegressionTableService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Command-Line Runner for executing benchmark evaluation matrices (§6, Step 12 M0, TigerStyle compliant).
 * Coordinates execution across targets, triggers automated scoring, and exports reports
 * with zero manual collation.
 */
@Component
@Slf4j
public class MatrixCli {

    public static final int EXIT_SUCCESS = 0;
    public static final int EXIT_THRESHOLD_FAILED = 1;
    public static final int EXIT_FATAL_ERROR = 2;

    private final MatrixRunner matrixRunner;
    private final RegressionTableService regressionTableService;
    private final HtmlReportGenerator htmlReportGenerator;
    private final DiagnosticTriageService diagnosticTriageService;

    public MatrixCli(MatrixRunner matrixRunner,
                     RegressionTableService regressionTableService,
                     HtmlReportGenerator htmlReportGenerator) {
        this(matrixRunner, regressionTableService, htmlReportGenerator, (DiagnosticTriageService) null);
    }

    @Autowired
    public MatrixCli(MatrixRunner matrixRunner,
                     RegressionTableService regressionTableService,
                     HtmlReportGenerator htmlReportGenerator,
                     Optional<DiagnosticTriageService> diagnosticTriageService) {
        this(matrixRunner, regressionTableService, htmlReportGenerator,
                diagnosticTriageService != null ? diagnosticTriageService.orElse(null) : null);
    }

    public MatrixCli(MatrixRunner matrixRunner,
                     RegressionTableService regressionTableService,
                     HtmlReportGenerator htmlReportGenerator,
                     DiagnosticTriageService diagnosticTriageService) {
        this.matrixRunner = Objects.requireNonNull(matrixRunner, "matrixRunner must not be null");
        this.regressionTableService = Objects.requireNonNull(regressionTableService, "regressionTableService must not be null");
        this.htmlReportGenerator = Objects.requireNonNull(htmlReportGenerator, "htmlReportGenerator must not be null");
        this.diagnosticTriageService = diagnosticTriageService;
    }

    /**
     * Executes the evaluation matrix and writes all reports to the destination directory.
     *
     * @param config matrix configuration
     * @return result summary containing scored reports and artifact paths
     * @throws Exception if matrix execution fails
     */
    public MatrixExecutionResult execute(MatrixConfig config) throws Exception {
        Objects.requireNonNull(config, "config must not be null");

        Path reportsDir = config.reportsDir();
        if (!Files.exists(reportsDir)) {
            Files.createDirectories(reportsDir);
        }

        log.info("[MatrixCli] Initiating matrix run for targets: {}", config.targetIds());
        MatrixScoreReport scoreReport = matrixRunner.runMatrix(
                config.targetIds(),
                config.loopConfig(),
                config.genParams()
        );

        List<String> runIds = new ArrayList<>(scoreReport.runScores().size());
        for (RunScore rs : scoreReport.runScores()) {
            runIds.add(rs.runId());
        }

        // Triage failed or evaluated runs if triage service is present
        List<DiagnosticTriage> triages = new ArrayList<>();
        if (diagnosticTriageService != null) {
            for (String runId : runIds) {
                triages.add(diagnosticTriageService.analyze(runId));
            }
        }

        // 1. Export Regression Table Markdown
        List<RegressionRow> regressionRows = regressionTableService.computeTable(runIds);
        String markdown = regressionTableService.renderMarkdown(regressionRows);

        StringBuilder mdBuilder = new StringBuilder(markdown);
        if (!triages.isEmpty()) {
            mdBuilder.append("\n\n## Diagnostic Failure Triage\n\n");
            mdBuilder.append("| Target | Run ID | Stage | Detail | Recommended Action |\n");
            mdBuilder.append("|---|---|---|---|---|\n");
            for (DiagnosticTriage dt : triages) {
                mdBuilder.append("| ").append(dt.targetId())
                        .append(" | ").append(dt.runId())
                        .append(" | ").append(dt.failureStage())
                        .append(" | ").append(dt.detail())
                        .append(" | ").append(dt.recommendedAction())
                        .append(" |\n");
            }
        }

        Path regMdPath = reportsDir.resolve("regression-table.md");
        Files.writeString(regMdPath, mdBuilder.toString(), StandardCharsets.UTF_8);
        log.info("[MatrixCli] Wrote regression table to {}", regMdPath);

        // 2. Export Matrix HTML Report
        String matrixHtml = htmlReportGenerator.generateMatrixReport(runIds);
        Path matrixHtmlPath = reportsDir.resolve("matrix-report.html");
        Files.writeString(matrixHtmlPath, matrixHtml, StandardCharsets.UTF_8);
        log.info("[MatrixCli] Wrote matrix HTML report to {}", matrixHtmlPath);

        // 3. Export Per-Run HTML Reports
        Path runsDir = reportsDir.resolve("runs");
        if (!Files.exists(runsDir)) {
            Files.createDirectories(runsDir);
        }

        List<Path> runReportPaths = new ArrayList<>(runIds.size());
        for (String runId : runIds) {
            Path runReportPath = runsDir.resolve("run-" + runId + ".html");
            htmlReportGenerator.exportRunReport(runId, runReportPath);
            runReportPaths.add(runReportPath);
        }
        log.info("[MatrixCli] Wrote {} individual run reports under {}", runReportPaths.size(), runsDir);

        return new MatrixExecutionResult(
                scoreReport,
                regressionRows,
                triages,
                matrixHtmlPath,
                regMdPath,
                runReportPaths
        );
    }

    /**
     * CLI run entrypoint with argument array and environment map.
     *
     * @param args CLI arguments
     * @param env  environment variables
     * @return exit code (0 = success, 1 = threshold failed, 2 = fatal error)
     */
    public int run(String[] args, Map<String, String> env) {
        try {
            MatrixConfig config = MatrixConfig.parse(args, env);
            MatrixExecutionResult result = execute(config);

            log.info("[MatrixCli] Matrix execution completed: accuracy={}/{} ({}), converged={}/{} ({})",
                    result.scoreReport().accurateCount(),
                    result.scoreReport().totalTargets(),
                    result.scoreReport().accuracyRate(),
                    result.scoreReport().convergedCount(),
                    result.scoreReport().totalTargets(),
                    result.scoreReport().convergenceRate()
            );

            if (result.isSuccess()) {
                log.info("[MatrixCli] PASSES V1 SUCCESS CRITERIA!");
                return EXIT_SUCCESS;
            } else {
                log.warn("[MatrixCli] FAILED to meet V1 success criteria (accuracy >= 70% and >= 2 converged)");
                return EXIT_THRESHOLD_FAILED;
            }
        } catch (Exception e) {
            log.error("[MatrixCli] Fatal error during matrix execution: {}", e.getMessage(), e);
            return EXIT_FATAL_ERROR;
        }
    }

    /**
     * CLI run entrypoint with system environment.
     */
    public int run(String[] args) {
        return run(args, System.getenv());
    }
}
