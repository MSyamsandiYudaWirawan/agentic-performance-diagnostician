package io.diag.agent.loop;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.service.EvidenceService;
import io.diag.runner.service.BenchmarkRunner;
import io.diag.runner.service.BuildFailedException;
import io.diag.runner.service.CodeTouchGate;
import io.diag.runner.service.JfrAnalyzer;
import io.diag.runner.service.TargetBuilder;
import io.diag.runner.service.TargetStack;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

public final class DockerTargetPipeline implements TargetPipeline, AutoCloseable {

    private final TargetStack     stack;
    private final BenchmarkRunner benchmarkRunner;
    private final TargetBuilder   targetBuilder;
    private final CodeTouchGate   codeTouchGate;
    private final JfrAnalyzer     jfrAnalyzer;
    private final EvidenceService evidenceService;
    private final Path            targetRepo;
    private final Path            evidenceRoot;
    private final String          runId;

    // cached by rebuild(); smoke() and benchmark() reference it
    private Path lastJar;

    public DockerTargetPipeline(TargetStack stack, BenchmarkRunner benchmarkRunner,
                                 TargetBuilder targetBuilder, CodeTouchGate codeTouchGate,
                                 JfrAnalyzer jfrAnalyzer, EvidenceService evidenceService,
                                 Path targetRepo, Path evidenceRoot, String runId) {
        this.stack           = Objects.requireNonNull(stack,           "stack");
        this.benchmarkRunner = Objects.requireNonNull(benchmarkRunner, "benchmarkRunner");
        this.targetBuilder   = Objects.requireNonNull(targetBuilder,   "targetBuilder");
        this.codeTouchGate   = Objects.requireNonNull(codeTouchGate,   "codeTouchGate");
        this.jfrAnalyzer     = Objects.requireNonNull(jfrAnalyzer,     "jfrAnalyzer");
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService");
        this.targetRepo      = Objects.requireNonNull(targetRepo,      "targetRepo");
        this.evidenceRoot    = Objects.requireNonNull(evidenceRoot,    "evidenceRoot");
        this.runId           = Objects.requireNonNull(runId,           "runId");
    }

    @Override
    public Path rebuild() throws BuildFailedException, Exception {
        Path buildLog = evidenceRoot.resolve(runId).resolve("build.log");
        if (buildLog.getParent() != null) {
            java.nio.file.Files.createDirectories(buildLog.getParent());
        }
        lastJar = targetBuilder.build(targetRepo, buildLog);
        return lastJar;
    }

    @Override
    public boolean runTests() throws Exception {
        Path testLog = evidenceRoot.resolve(runId).resolve("test.log");
        if (testLog.getParent() != null) {
            java.nio.file.Files.createDirectories(testLog.getParent());
        }
        return codeTouchGate.runTests(targetRepo, testLog);
    }

    @Override
    public SmokeResult smoke(int n) throws Exception {
        String label = "smoke-" + n;
        stack.up(label, lastJar);
        LoadReport report = benchmarkRunner.run(label, true);
        stack.down();
        String verdict = report.getPayload().thresholds().verdict();
        return new SmokeResult(!"NOT_TESTABLE".equals(verdict), verdict);
    }

    @Override
    public BenchmarkCycle benchmark(String label, JfrReportDto prevJfr) throws Exception {
        evidenceService.createTrajectoryEvent(runId, "TOOL_CALL",
                Map.of("action", "benchmark", "label", label), 0L, 0L, BigDecimal.ZERO);

        stack.up(label, lastJar);
        LoadReport loadReport = benchmarkRunner.run(label, false);
        Path jfrPath          = stack.stopAndHarvest(label);
        JfrReportDto jfrDto   = jfrAnalyzer.analyze(jfrPath, prevJfr, runId, label);
        JfrReport jfrEntity   = evidenceService.createJfrReport(runId, label, jfrDto, jfrPath);
        stack.down();

        evidenceService.createTrajectoryEvent(runId, "TOOL_RESULT",
                Map.of("label", label, "rps", loadReport.getPayload().rps()), 0L, 0L, BigDecimal.ZERO);

        return new BenchmarkCycle(loadReport.getPayload(), loadReport.getId(),
                                  jfrDto, jfrEntity.getId());
    }

    @Override
    public void close() {
        try {
            stack.close();
        } catch (Exception e) {
            // best-effort — close() must not throw
            System.err.println("[DockerTargetPipeline] stack.close() failed: " + e.getMessage());
        }
    }
}
