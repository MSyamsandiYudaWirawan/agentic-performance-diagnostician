package io.diag.agent.loop;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.dto.ThresholdsDto;
import io.diag.runner.service.BuildFailedException;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * Scriptable test double for TargetPipeline (§10, M3).
 * Simulates rebuild, tests, smoke, and benchmark without Docker or Maven.
 */
public final class FakeTargetPipeline implements TargetPipeline {

    private final AtomicLong nextReportId = new AtomicLong(5000L);

    private BuildFailedException rebuildException;
    private boolean testsPass = true;
    private SmokeResult smokeResult = new SmokeResult(true, "ok");
    private final Queue<BenchmarkCycle> cannedBenchmarks = new ArrayDeque<>();
    private BiFunction<String, JfrReportDto, BenchmarkCycle> benchmarkResponder;

    private int rebuildCount = 0;
    private int runTestsCount = 0;
    private int smokeCount = 0;
    private int benchmarkCount = 0;
    private final List<String> benchmarkLabels = new ArrayList<>();
    private final List<JfrReportDto> recordedPrevJfrs = new ArrayList<>();

    private io.diag.evidence.service.EvidenceService evidenceService;
    private java.util.function.Supplier<String> runIdSupplier;
    private int successfulRebuildsBeforeFail = -1;
    private BuildFailedException failRebuildException;

    public FakeTargetPipeline withEvidenceService(io.diag.evidence.service.EvidenceService es,
                                                  java.util.function.Supplier<String> runIdSupplier) {
        this.evidenceService = es;
        this.runIdSupplier = runIdSupplier;
        return this;
    }

    public FakeTargetPipeline setFailRebuildAfter(int successfulRebuilds, BuildFailedException e) {
        this.successfulRebuildsBeforeFail = successfulRebuilds;
        this.failRebuildException = e;
        return this;
    }

    public FakeTargetPipeline setRebuildException(BuildFailedException e) {
        this.rebuildException = e;
        return this;
    }

    public FakeTargetPipeline setTestsPass(boolean pass) {
        this.testsPass = pass;
        return this;
    }

    public FakeTargetPipeline setSmokeResult(SmokeResult res) {
        this.smokeResult = Objects.requireNonNull(res, "res must not be null");
        return this;
    }

    public FakeTargetPipeline enqueueBenchmark(BenchmarkCycle cycle) {
        Objects.requireNonNull(cycle, "cycle must not be null");
        cannedBenchmarks.add(cycle);
        return this;
    }

    public FakeTargetPipeline setBenchmarkResponder(BiFunction<String, JfrReportDto, BenchmarkCycle> responder) {
        this.benchmarkResponder = Objects.requireNonNull(responder, "responder must not be null");
        return this;
    }

    @Override
    public Path rebuild() throws BuildFailedException {
        rebuildCount++;
        if (successfulRebuildsBeforeFail >= 0 && rebuildCount > successfulRebuildsBeforeFail) {
            throw failRebuildException;
        }
        if (rebuildException != null) {
            throw rebuildException;
        }
        return Path.of("target/fake-app.jar");
    }

    @Override
    public boolean runTests() {
        runTestsCount++;
        return testsPass;
    }

    @Override
    public SmokeResult smoke(int n) {
        smokeCount++;
        return smokeResult;
    }

    @Override
    public BenchmarkCycle benchmark(String label, JfrReportDto prevJfr) {
        benchmarkCount++;
        benchmarkLabels.add(label);
        recordedPrevJfrs.add(prevJfr);

        BenchmarkCycle cycle;
        if (!cannedBenchmarks.isEmpty()) {
            cycle = cannedBenchmarks.poll();
        } else if (benchmarkResponder != null) {
            cycle = benchmarkResponder.apply(label, prevJfr);
        } else {
            cycle = createCycle(label, 200.0, 2500.0, 50.0, 0.0, Map.of("JavaMonitorEnter", 70000L));
        }

        if (evidenceService != null && runIdSupplier != null && runIdSupplier.get() != null) {
            var lr = evidenceService.createLoadReport(runIdSupplier.get(), label, cycle.load(), Path.of("k6-summary.json"));
            var jr = evidenceService.createJfrReport(runIdSupplier.get(), label, cycle.jfr(), Path.of("profile.jfr"));
            return new BenchmarkCycle(cycle.load(), lr.getId(), cycle.jfr(), jr.getId());
        }
        return cycle;
    }

    // -------------------------------------------------------------------------
    // Factory helper for canned cycles
    // -------------------------------------------------------------------------

    public BenchmarkCycle createCycle(String label, double rps, double p95Ms, double p50Ms,
                                      double failRate, Map<String, Long> signalCounts) {
        long loadId = nextReportId.incrementAndGet();
        long jfrId  = nextReportId.incrementAndGet();

        LoadReportDto load = new LoadReportDto(
                "fake-repo", "2026-09-29T00:00:00Z",
                rps, (long) (rps * 60),
                new LatencyDto(p50Ms * 1.1, p50Ms, p95Ms, p95Ms * 1.2, p95Ms * 2.0),
                failRate, 1.0,
                new ThresholdsDto("PASS", List.of()));

        Map<String, SignalSummaryDto> signals = new LinkedHashMap<>();
        if (signalCounts != null) {
            for (Map.Entry<String, Long> e : signalCounts.entrySet()) {
                signals.put(e.getKey(), new SignalSummaryDto(
                        e.getValue(), 10.0, 20.0, 30.0, 50.0, "HIGH", List.of("com.example.Hotspot.run")));
            }
        }
        JfrReportDto jfr = new JfrReportDto("fake-run", label, signals, null);

        return new BenchmarkCycle(load, loadId, jfr, jfrId);
    }

    // -------------------------------------------------------------------------
    // Counters & inspection
    // -------------------------------------------------------------------------

    public int getRebuildCount() { return rebuildCount; }
    public int getRunTestsCount() { return runTestsCount; }
    public int getSmokeCount() { return smokeCount; }
    public int getBenchmarkCount() { return benchmarkCount; }
    public List<String> getBenchmarkLabels() { return new ArrayList<>(benchmarkLabels); }
    public List<JfrReportDto> getRecordedPrevJfrs() { return new ArrayList<>(recordedPrevJfrs); }
}
