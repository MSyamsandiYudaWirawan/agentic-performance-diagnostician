package io.diag.agent.loop;

import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;
import io.diag.evidence.service.EvidenceService;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory test double for EvidenceService (§10, M3).
 * No Postgres, no disk I/O. Thread-safe in-memory collections.
 */
public final class FakeEvidenceService implements EvidenceService {

    private final AtomicLong nextId = new AtomicLong(1000L);

    private final Map<String, Run> runs = new LinkedHashMap<>();
    private final Map<String, List<LoadReport>> loadReports = new LinkedHashMap<>();
    private final Map<String, List<JfrReport>> jfrReports = new LinkedHashMap<>();
    private final Map<String, List<Iteration>> iterations = new LinkedHashMap<>();
    private final List<TrajectoryEvent> trajectoryEvents = Collections.synchronizedList(new ArrayList<>());

    @Override
    public synchronized Run createRun(String targetId, String provider, String model,
                                      String promptHash, String aggregatorVersion, Map<String, Object> genParams) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(promptHash, "promptHash must not be null");

        // single-flight lock (§10.27)
        for (Run r : runs.values()) {
            if (RunStatus.RUNNING.name().equals(r.getStatus())) {
                throw new IllegalStateException("a run is already RUNNING — single-flight lock (scope §10.27)");
            }
        }

        String runId = "20260929-120000-" + String.format("%04d", nextId.incrementAndGet());
        Run run = Run.builder()
                .id(runId)
                .targetId(targetId)
                .provider(provider)
                .model(model)
                .promptHash(promptHash)
                .aggregatorVersion(aggregatorVersion)
                .genParams(genParams != null ? genParams : Map.of())
                .status(RunStatus.RUNNING.name())
                .startedAt(Instant.now())
                .build();
        runs.put(runId, run);
        return run;
    }

    @Override
    public synchronized void transitionRunStatus(String runId, RunStatus beforeStatus, RunStatus afterStatus) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(beforeStatus, "beforeStatus must not be null");
        Objects.requireNonNull(afterStatus, "afterStatus must not be null");

        Run run = runs.get(runId);
        if (run == null) {
            throw new IllegalStateException("run " + runId + " not found");
        }
        if (!beforeStatus.name().equals(run.getStatus())) {
            throw new IllegalStateException(
                    "run " + runId + " was not in status " + beforeStatus + ", cannot transition to " + afterStatus);
        }
        run.setStatus(afterStatus.name());
        run.setFinishedAt(Instant.now());
    }

    @Override
    public synchronized LoadReport createLoadReport(String runId, String label, LoadReportDto dto, Path k6SummaryFile) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(dto, "dto must not be null");

        long id = nextId.incrementAndGet();
        LoadReport report = LoadReport.builder()
                .id(id)
                .runId(runId)
                .label(label)
                .payload(dto)
                .k6SummaryPath(k6SummaryFile != null ? k6SummaryFile.toString() : "fake/k6-summary.json")
                .build();
        loadReports.computeIfAbsent(runId, k -> new ArrayList<>()).add(report);
        return report;
    }

    @Override
    public synchronized JfrReport createJfrReport(String runId, String label, JfrReportDto dto, Path jfrFile) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(dto, "dto must not be null");

        long id = nextId.incrementAndGet();
        JfrReport report = JfrReport.builder()
                .id(id)
                .runId(runId)
                .label(label)
                .payload(dto)
                .jfrPath(jfrFile != null ? jfrFile.toString() : "fake/profile.jfr")
                .jfrSha256("fake-jfr-sha256")
                .build();
        jfrReports.computeIfAbsent(runId, k -> new ArrayList<>()).add(report);
        return report;
    }

    @Override
    public synchronized Iteration createIteration(String runId, int n, HypothesisDto hypothesis, Map<String, Object> ledger,
                                                  ChangeDto change, String outcome, String treeSha,
                                                  Long loadReportId, Long jfrReportId,
                                                  List<FilesTouchedDto> filesTouched, String keepType, String finding) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(outcome, "outcome must not be null");

        long id = nextId.incrementAndGet();
        Iteration iter = Iteration.builder()
                .id(id)
                .runId(runId)
                .n(n)
                .hypothesis(hypothesis)
                .ledger(ledger)
                .change(change)
                .outcome(outcome)
                .treeSha(treeSha)
                .loadReportId(loadReportId)
                .jfrReportId(jfrReportId)
                .filesTouched(FilesTouchedList.of(filesTouched != null ? filesTouched : List.of()))
                .keepType(keepType)
                .finding(finding)
                .createdAt(Instant.now())
                .build();
        iterations.computeIfAbsent(runId, k -> new ArrayList<>()).add(iter);
        return iter;
    }

    @Override
    public TrajectoryEvent createTrajectoryEvent(String runId, String kind, Map<String, Object> payload,
                                                 Long tokensIn, Long tokensOut, BigDecimal costUsd) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");

        TrajectoryEvent event = TrajectoryEvent.builder()
                .id(nextId.incrementAndGet())
                .runId(runId)
                .kind(kind)
                .payload(payload != null ? payload : Map.of())
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .costUsd(costUsd)
                .ts(Instant.now())
                .build();
        trajectoryEvents.add(event);
        return event;
    }

    @Override
    public synchronized void recordBaseline(String runId, double p95Ms, double p95FloorMs, double rps, double rpsFloor, String originSha) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(originSha, "originSha must not be null");

        Run run = runs.get(runId);
        if (run == null) {
            throw new IllegalStateException("run " + runId + " not found — cannot record baseline");
        }
        run.setBaselineP95Ms(p95Ms);
        run.setNoiseFloorMs(p95FloorMs);
        run.setBaselineRps(rps);
        run.setNoiseFloorRps(rpsFloor);
        run.setOriginSha(originSha);
        run.setNew(false);
    }

    @Override
    public synchronized void recordKeptSha(String runId, String sha) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(sha, "sha must not be null");

        Run run = runs.get(runId);
        if (run == null) {
            throw new IllegalStateException("run " + runId + " not found — cannot record kept sha");
        }
        run.setLastKeptSha(sha);
        run.setNew(false);
    }

    @Override
    public synchronized Optional<Run> findRun(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public synchronized Optional<Run> findRunningRun() {
        for (Run r : runs.values()) {
            if (RunStatus.RUNNING.name().equals(r.getStatus())) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized List<Iteration> findIterations(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        List<Iteration> list = iterations.get(runId);
        return list != null ? new ArrayList<>(list) : List.of();
    }

    @Override
    public synchronized List<LoadReport> findLoadReports(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");
        List<LoadReport> list = loadReports.get(runId);
        return list != null ? new ArrayList<>(list) : List.of();
    }

    @Override
    public synchronized Optional<JfrReport> findJfrReport(String runId, String label) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        List<JfrReport> list = jfrReports.get(runId);
        if (list == null) return Optional.empty();
        for (JfrReport report : list) {
            if (label.equals(report.getLabel())) {
                return Optional.of(report);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<TrajectoryEvent> findTrajectoryEvents(String runId) {
        synchronized (trajectoryEvents) {
            List<TrajectoryEvent> matched = new ArrayList<>();
            for (TrajectoryEvent e : trajectoryEvents) {
                if (runId.equals(e.getRunId())) {
                    matched.add(e);
                }
            }
            return matched;
        }
    }

    // -------------------------------------------------------------------------
    // Test inspection helpers
    // -------------------------------------------------------------------------

    public List<TrajectoryEvent> trajectoryEvents() {
        synchronized (trajectoryEvents) {
            return new ArrayList<>(trajectoryEvents);
        }
    }

    public List<Iteration> iterationsFor(String runId) {
        return findIterations(runId);
    }

    public Run getRun(String runId) {
        return runs.get(runId);
    }
}
