package io.diag.evidence.service;

import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.TrajectoryEvent;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public interface EvidenceService {

    Run createRun(String targetId, String provider, String model,
                  String promptHash, String aggregatorVersion, Map<String, Object> genParams);

    void transitionRunStatus(String runId, RunStatus beforeStatus, RunStatus afterStatus);

    LoadReport createLoadReport(String runId, String label, LoadReportDto dto, Path k6SummaryFile);

    JfrReport createJfrReport(String runId, String label, JfrReportDto dto, Path jfrFile);

    Iteration createIteration(String runId, int n, HypothesisDto hypothesis, Map<String, Object> ledger,
                              ChangeDto change, String outcome, String treeSha,
                              Long loadReportId, Long jfrReportId,
                              List<FilesTouchedDto> filesTouched, String keepType, String finding);

    TrajectoryEvent createTrajectoryEvent(String runId, String kind, Map<String, Object> payload,
                                          Long tokensIn, Long tokensOut, BigDecimal costUsd);

    void recordBaseline(String runId, double p95Ms, double p95FloorMs, double rps, double rpsFloor, String originSha);

    void recordKeptSha(String runId, String sha);
}
