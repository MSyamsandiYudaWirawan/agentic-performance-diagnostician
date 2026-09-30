package io.diag.eval;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.LoopConfig;
import io.diag.eval.cli.MatrixCli;
import io.diag.eval.cli.MatrixConfig;
import io.diag.eval.cli.MatrixExecutionResult;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RegressionKey;
import io.diag.eval.model.RegressionRow;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.HtmlReportGenerator;
import io.diag.eval.service.MatrixRunner;
import io.diag.eval.service.RegressionTableService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 12 Milestone 0 Verify Gate (MatrixCliTest).
 * Tests configuration parsing, CLI execution flow, report exports, and exit codes.
 */
public class MatrixCliTest {

    private MatrixRunner matrixRunner;
    private RegressionTableService regressionTableService;
    private HtmlReportGenerator htmlReportGenerator;
    private MatrixCli matrixCli;

    @BeforeEach
    void setUp() {
        matrixRunner = Mockito.mock(MatrixRunner.class);
        regressionTableService = Mockito.mock(RegressionTableService.class);
        htmlReportGenerator = Mockito.mock(HtmlReportGenerator.class);

        matrixCli = new MatrixCli(matrixRunner, regressionTableService, htmlReportGenerator);
    }

    @Test
    void parseConfig_fromCliArgsAndEnv() {
        String[] args = new String[]{
                "--targets=S1,S2",
                "--reports-dir=custom/reports",
                "--provider=custom-provider",
                "--model=custom-model",
                "--max-iterations=4",
                "--max-wall-ms=600000",
                "--max-tokens=300000",
                "--max-cost=15.00",
                "--dry-run=true"
        };
        Map<String, String> env = Map.of();

        MatrixConfig config = MatrixConfig.parse(args, env);

        assertThat(config.targetIds()).containsExactly("S1", "S2");
        assertThat(config.reportsDir()).isEqualTo(Path.of("custom/reports"));
        assertThat(config.genParams().provider()).isEqualTo("custom-provider");
        assertThat(config.genParams().model()).isEqualTo("custom-model");
        assertThat(config.loopConfig().maxIterations()).isEqualTo(4);
        assertThat(config.loopConfig().maxWallMs()).isEqualTo(600_000L);
        assertThat(config.loopConfig().maxTokens()).isEqualTo(300_000L);
        assertThat(config.loopConfig().maxCostUsd()).isEqualByComparingTo("15.00");
        assertThat(config.dryRun()).isTrue();
    }

    @Test
    void parseConfig_defaultsWhenEmpty() {
        MatrixConfig config = MatrixConfig.parse(new String[0], Map.of());

        assertThat(config.targetIds()).containsExactly("S1", "S2", "S3", "S4");
        assertThat(config.reportsDir()).isEqualTo(Path.of("target", "reports"));
        assertThat(config.genParams().provider()).isEqualTo("anthropic");
        assertThat(config.genParams().model()).isEqualTo("claude-3-7-sonnet");
        assertThat(config.loopConfig().maxIterations()).isEqualTo(5);
        assertThat(config.dryRun()).isFalse();
    }

    @Test
    void execute_exportsAllArtifactsAndReturnsResult(@TempDir Path tempDir) throws Exception {
        Path reportsDir = tempDir.resolve("out-reports");
        MatrixConfig config = new MatrixConfig(
                List.of("S1", "S2"),
                reportsDir,
                new LoopConfig(3, 30_000L, 500_000L, new BigDecimal("5.00"), 5, 0.50, BigDecimal.ZERO, BigDecimal.ZERO),
                new GenParams("anthropic", "claude-3-7-sonnet", 0.0, 2000),
                false
        );

        Instant now = Instant.now();
        RunScore score1 = new RunScore(
                "run-s1", "S1", "COMPLETED", "H5", "H5", true,
                180.0, 120.0, 60.0, 15.0, true,
                200.0, 250.0, 50.0, 10.0, true,
                1, 25000L, new BigDecimal("0.25"), now, now
        );
        RunScore score2 = new RunScore(
                "run-s2", "S2", "COMPLETED", "H2", "H2", true,
                300.0, 150.0, 150.0, 25.0, true,
                100.0, 220.0, 120.0, 10.0, true,
                1, 20000L, new BigDecimal("0.20"), now, now
        );
        MatrixScoreReport scoreReport = new MatrixScoreReport(
                2, 2, 1.0, 2, 1.0, List.of(score1, score2)
        );
        when(matrixRunner.runMatrix(any(), any(), any())).thenReturn(scoreReport);

        RegressionRow row1 = new RegressionRow(
                new RegressionKey("phash-1", "claude-3-7-sonnet", "1.0"),
                "S1", 1, 1.0, 1.0, 60.0, 50.0, 1.0, 25000L, new BigDecimal("0.25")
        );
        when(regressionTableService.computeTable(List.of("run-s1", "run-s2"))).thenReturn(List.of(row1));
        when(regressionTableService.renderMarkdown(any())).thenReturn("# Regression Table\n| S1 | accurate |");

        when(htmlReportGenerator.generateMatrixReport(List.of("run-s1", "run-s2"))).thenReturn("<html>Matrix Report</html>");

        MatrixExecutionResult result = matrixCli.execute(config);

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.scoreReport().passesV1Threshold()).isTrue();

        // Verify artifact exports on disk
        assertThat(Files.exists(result.regressionTableMdPath())).isTrue();
        assertThat(Files.readString(result.regressionTableMdPath())).contains("# Regression Table");

        assertThat(Files.exists(result.matrixReportHtmlPath())).isTrue();
        assertThat(Files.readString(result.matrixReportHtmlPath())).contains("<html>Matrix Report</html>");

        assertThat(result.runReportPaths()).hasSize(2);
        verify(htmlReportGenerator).exportRunReport(eq("run-s1"), eq(reportsDir.resolve("runs/run-run-s1.html")));
        verify(htmlReportGenerator).exportRunReport(eq("run-s2"), eq(reportsDir.resolve("runs/run-run-s2.html")));
    }

    @Test
    void run_exitCodes_successAndThresholdFail(@TempDir Path tempDir) throws Exception {
        String[] args = new String[]{"--reports-dir=" + tempDir.resolve("reports").toString()};

        // Scenario 1: Passing threshold -> exit 0
        MatrixScoreReport passingReport = new MatrixScoreReport(
                2, 2, 1.0, 2, 1.0, List.of()
        );
        when(matrixRunner.runMatrix(any(), any(), any())).thenReturn(passingReport);
        when(regressionTableService.computeTable(any())).thenReturn(List.of());
        when(regressionTableService.renderMarkdown(any())).thenReturn("");
        when(htmlReportGenerator.generateMatrixReport(any())).thenReturn("");

        int exitCodeSuccess = matrixCli.run(args, Map.of());
        assertThat(exitCodeSuccess).isEqualTo(MatrixCli.EXIT_SUCCESS);

        // Scenario 2: Failing threshold -> exit 1
        MatrixScoreReport failingReport = new MatrixScoreReport(
                2, 0, 0.0, 0, 0.0, List.of()
        );
        when(matrixRunner.runMatrix(any(), any(), any())).thenReturn(failingReport);

        int exitCodeFail = matrixCli.run(args, Map.of());
        assertThat(exitCodeFail).isEqualTo(MatrixCli.EXIT_THRESHOLD_FAILED);

        // Scenario 3: Fatal exception -> exit 2
        when(matrixRunner.runMatrix(any(), any(), any())).thenThrow(new RuntimeException("Docker daemon down"));

        int exitCodeFatal = matrixCli.run(args, Map.of());
        assertThat(exitCodeFatal).isEqualTo(MatrixCli.EXIT_FATAL_ERROR);
    }

    @Test
    void nullValidation_throwsOnNullArgs() {
        assertThatThrownBy(() -> matrixCli.execute(null))
                .isInstanceOf(NullPointerException.class);
    }
}
