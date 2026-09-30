package io.diag.eval;

import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.impl.HtmlReportGeneratorImpl;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TrajectoryEventRepository;
import io.diag.evidence.service.TargetRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Step 11 Milestone 2 Verify Gate (HtmlReportGeneratorTest).
 * Tests offline single-file report generation, zero CDN dependencies,
 * inline SVG charts, timeline tables, and trajectory event logs.
 */
public class HtmlReportGeneratorTest {

    private RunRepository runRepository;
    private IterationRepository iterationRepository;
    private LoadReportRepository loadReportRepository;
    private TrajectoryEventRepository trajectoryEventRepository;
    private TargetRegistry targetRegistry;
    private EvalScorer evalScorer;
    private HtmlReportGeneratorImpl generator;

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(RunRepository.class);
        iterationRepository = Mockito.mock(IterationRepository.class);
        loadReportRepository = Mockito.mock(LoadReportRepository.class);
        trajectoryEventRepository = Mockito.mock(TrajectoryEventRepository.class);
        targetRegistry = Mockito.mock(TargetRegistry.class);
        evalScorer = Mockito.mock(EvalScorer.class);

        generator = new HtmlReportGeneratorImpl(
                runRepository,
                iterationRepository,
                loadReportRepository,
                trajectoryEventRepository,
                targetRegistry,
                evalScorer
        );
    }

    @Test
    void generateRunReport_producesValidSelfContainedHtml() {
        String runId = "run-m2-001";
        Instant now = Instant.parse("2026-09-30T10:00:00Z");

        Run run = Run.builder()
                .id(runId)
                .targetId("S1")
                .provider("anthropic")
                .model("claude-3-7-sonnet")
                .promptHash("phash-abc")
                .aggregatorVersion("1.0")
                .status("COMPLETED")
                .startedAt(now)
                .finishedAt(now.plusSeconds(120))
                .baselineP95Ms(150.0)
                .noiseFloorMs(20.0)
                .baselineRps(200.0)
                .noiseFloorRps(10.0)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        Target target = Target.builder()
                .id("S1")
                .name("Stock Spring Petclinic")
                .build();
        when(targetRegistry.findById("S1")).thenReturn(Optional.of(target));

        RunScore score = new RunScore(
                runId, "S1", "COMPLETED",
                "H5", "H5", true,
                150.0, 110.0, -40.0, 20.0, true,
                200.0, 260.0, 60.0, 10.0, true,
                2, 45000L, new BigDecimal("0.45"),
                now, now.plusSeconds(120)
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);

        LoadReportDto lrDto = new LoadReportDto(
                "repo", "2026-09-30", 260.0, 5000,
                new LatencyDto(110.0, 90.0, 80.0, 150.0, 170.0),
                0.0, 1.0, null
        );
        LoadReport lr = LoadReport.builder()
                .id(101L)
                .runId(runId)
                .label("iter-1")
                .payload(lrDto)
                .k6SummaryPath("runs/run-m2-001/k6-summary.json")
                .build();
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId)).thenReturn(List.of(lr));

        Iteration iter = Iteration.builder()
                .id(1L)
                .runId(runId)
                .n(1)
                .hypothesis(new HypothesisDto("H5", 0.9, "jar-unpack avoids zip contention"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("KEPT")
                .keepType("REDUCED_LATENCY")
                .finding("Latency dropped by 40ms")
                .loadReportId(101L)
                .build();
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of(iter));

        TrajectoryEvent event = TrajectoryEvent.builder()
                .id(1L)
                .runId(runId)
                .ts(now.plusSeconds(5))
                .kind("LLM_RESP")
                .tokensIn(1200L)
                .tokensOut(350L)
                .costUsd(new BigDecimal("0.02"))
                .payload(Map.of("hypothesis", "H5"))
                .build();
        when(trajectoryEventRepository.findByRunIdOrderByTsAsc(runId)).thenReturn(List.of(event));

        String html = generator.generateRunReport(runId);

        assertThat(html).isNotNull();
        assertThat(html).startsWith("<!DOCTYPE html>");
        assertThat(html).contains("<title>Run Report — run-m2-001</title>");

        // Verify zero external network calls/CDNs
        assertThat(html).doesNotContain("href=\"http");
        assertThat(html).doesNotContain("src=\"http");
        assertThat(html).doesNotContain("@import");
        assertThat(html).doesNotContain("<script");

        // Verify Scorecards
        assertThat(html).contains("ACCURATE");
        assertThat(html).contains("CONVERGED");
        assertThat(html).contains("Stock Spring Petclinic (S1)");
        assertThat(html).contains("claude-3-7-sonnet");
        assertThat(html).contains("phash-abc");

        // Verify inline SVG charts
        assertThat(html).contains("<svg viewBox=\"0 0 440 180\"");
        assertThat(html).contains("Floor");

        // Verify Timeline & Trajectory
        assertThat(html).contains("jar-unpack avoids zip contention");
        assertThat(html).contains("k6-summary.json");
        assertThat(html).contains("LLM_RESP");
        assertThat(html).contains("1200 / 350");
    }

    @Test
    void exportRunReport_writesFileToDisk(@TempDir Path tempDir) throws IOException {
        String runId = "run-export-002";
        Run run = Run.builder()
                .id(runId)
                .targetId("S2")
                .status("COMPLETED")
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        RunScore score = new RunScore(
                runId, "S2", "COMPLETED",
                "H2", "H2", true,
                300.0, 150.0, -150.0, 25.0, true,
                100.0, 200.0, 100.0, 10.0, true,
                1, 20000L, new BigDecimal("0.20"),
                Instant.now(), Instant.now()
        );
        when(evalScorer.scoreRun(runId)).thenReturn(score);
        when(iterationRepository.findByRunIdOrderByNAsc(runId)).thenReturn(List.of());
        when(loadReportRepository.findByRunIdOrderByIdAsc(runId)).thenReturn(List.of());
        when(trajectoryEventRepository.findByRunIdOrderByTsAsc(runId)).thenReturn(List.of());

        Path destFile = tempDir.resolve("reports/sub/report.html");
        Path written = generator.exportRunReport(runId, destFile);

        assertThat(written).isEqualTo(destFile);
        assertThat(Files.exists(written)).isTrue();
        String content = Files.readString(written);
        assertThat(content).contains("Run ID: <code>run-export-002</code>");
        assertThat(content).contains("<!DOCTYPE html>");
    }

    @Test
    void generateMatrixReport_rendersSummaryTableAndBadges() {
        List<String> runIds = List.of("r1", "r2");
        RunScore s1 = new RunScore(
                "r1", "S1", "COMPLETED", "H5", "H5", true,
                150.0, 100.0, -50.0, 20.0, true,
                200.0, 250.0, 50.0, 10.0, true,
                2, 40000L, new BigDecimal("0.40"),
                Instant.now(), Instant.now()
        );
        RunScore s2 = new RunScore(
                "r2", "S2", "COMPLETED", "H2", "H2", true,
                300.0, 150.0, -150.0, 25.0, true,
                100.0, 200.0, 100.0, 10.0, true,
                1, 20000L, new BigDecimal("0.20"),
                Instant.now(), Instant.now()
        );
        MatrixScoreReport matrixReport = new MatrixScoreReport(
                2, 2, 1.0, 2, 1.0, List.of(s1, s2)
        );
        when(evalScorer.scoreRuns(runIds)).thenReturn(matrixReport);

        String html = generator.generateMatrixReport(runIds);

        assertThat(html).isNotNull();
        assertThat(html).contains("Evaluation Matrix Scorecard");
        assertThat(html).contains("PASSES V1 THRESHOLD");
        assertThat(html).contains("100.0%");
        assertThat(html).contains("r1");
        assertThat(html).doesNotContain("href=\"http");
        assertThat(html).doesNotContain("src=\"http");
        assertThat(html).doesNotContain("@import");
        assertThat(html).doesNotContain("<script");
    }

    @Test
    void nullValidation_throwsOnNullArgs() {
        assertThatThrownBy(() -> generator.generateRunReport(null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> generator.exportRunReport(null, Path.of("test.html")))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> generator.exportRunReport("run-1", null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> generator.generateMatrixReport(null))
                .isInstanceOf(NullPointerException.class);
    }
}
