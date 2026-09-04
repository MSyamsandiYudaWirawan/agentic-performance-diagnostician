package io.diag.evidence.service.impl;

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
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.JfrReportRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TrajectoryEventRepository;
import io.diag.evidence.service.ArtifactStore;
import io.diag.evidence.service.EvidenceService;
import io.diag.evidence.service.RunIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class EvidenceServiceImpl implements EvidenceService {
    private final RunRepository runRepository;
    private final LoadReportRepository loadReportRepository;
    private final JfrReportRepository jfrReportRepository;
    private final IterationRepository iterationRepository;
    private final TrajectoryEventRepository trajectoryEventRepository;
    private final RunIdGenerator runIdGenerator;
    private final ArtifactStore artifactStore;

    @Transactional
    @Override
    public Run createRun(String targetId, String provider, String model,
                         String promptHash, String aggregatorVersion, Map<String, Object> genParams) {
        Objects.requireNonNull(targetId, "targetId must not be null");
        Objects.requireNonNull(promptHash, "promptHash must not be null");

        // single-flight lock (scope §10.27): the RUNNING row is the lock — a second
        // run refuses to start while one exists
        if (runRepository.findFirstByStatus(RunStatus.RUNNING.name()).isPresent()) {
            throw new IllegalStateException("a run is already RUNNING — single-flight lock (scope §10.27)");
        }

        Run run = Run.builder()
                .id(runIdGenerator.next())
                .targetId(targetId)
                .provider(provider)
                .model(model)
                .promptHash(promptHash)
                .aggregatorVersion(aggregatorVersion)
                .genParams(genParams)
                .status(RunStatus.RUNNING.name())
                .startedAt(Instant.now())
                .build();

        return runRepository.save(run);
    }

    @Override
    public void transitionRunStatus(String runId, RunStatus beforeStatus, RunStatus afterStatus) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(beforeStatus, "beforeStatus must not be null");
        Objects.requireNonNull(afterStatus, "afterStatus must not be null");

        // conditional UPDATE: 0 rows means the run was not in beforeStatus — a
        // lost race or stale transition, not a DB error, so the caller gets a
        // specific exception carrying both states
        int updated = runRepository.updateRunStatus(runId, beforeStatus.name(), afterStatus.name(), Instant.now());
        if (updated == 0) {
            throw new IllegalStateException(
                    "run " + runId + " was not in status " + beforeStatus + ", cannot transition to " + afterStatus);
        }
    }

    @Override
    public LoadReport createLoadReport(String runId, String label, LoadReportDto dto, Path k6SummaryFile) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(dto, "dto must not be null");
        Objects.requireNonNull(k6SummaryFile, "k6SummaryFile must not be null");

        ArtifactStore.StoredArtifact stored = artifactStore.store(runId, label, k6SummaryFile);

        LoadReport loadReport = LoadReport.builder()
                .runId(runId)
                .label(label)
                .payload(dto)
                .k6SummaryPath(stored.path().toString())
                .build();
        return loadReportRepository.save(loadReport);
    }

    @Override
    public JfrReport createJfrReport(String runId, String label, JfrReportDto dto, Path jfrFile) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(label, "label must not be null");
        Objects.requireNonNull(dto, "dto must not be null");
        Objects.requireNonNull(jfrFile, "jfrFile must not be null");

        ArtifactStore.StoredArtifact stored = artifactStore.store(runId, label, jfrFile);

        JfrReport jfrReport = JfrReport.builder()
                .runId(runId)
                .label(label)
                .payload(dto)
                .jfrPath(stored.path().toString())
                .jfrSha256(stored.sha256())
                .build();
        return jfrReportRepository.save(jfrReport);
    }

    @Override
    public Iteration createIteration(String runId, int n, HypothesisDto hypothesis, Map<String, Object> ledger,
                                     ChangeDto change, String outcome, String treeSha,
                                     Long loadReportId, Long jfrReportId,
                                     List<FilesTouchedDto> filesTouched, String keepType, String finding) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(hypothesis, "hypothesis must not be null");
        Objects.requireNonNull(change, "change must not be null");

        Iteration iteration = Iteration.builder()
                .runId(runId)
                .n(n)
                .hypothesis(hypothesis)
                .ledger(ledger)
                .change(change)
                .outcome(outcome)
                .treeSha(treeSha)
                .loadReportId(loadReportId)
                .jfrReportId(jfrReportId)
                .filesTouched(FilesTouchedList.of(filesTouched))
                .keepType(keepType)
                .finding(finding)
                .createdAt(Instant.now())
                .build();
        return iterationRepository.save(iteration);
    }

    @Override
    public TrajectoryEvent createTrajectoryEvent(String runId, String kind, Map<String, Object> payload,
                                                 Long tokensIn, Long tokensOut, BigDecimal costUsd) {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(kind, "kind must not be null");

        TrajectoryEvent event = TrajectoryEvent.builder()
                .runId(runId)
                .kind(kind)
                .payload(payload)
                .tokensIn(tokensIn)
                .tokensOut(tokensOut)
                .costUsd(costUsd)
                .ts(Instant.now())
                .build();
        return trajectoryEventRepository.save(event);
    }
}
