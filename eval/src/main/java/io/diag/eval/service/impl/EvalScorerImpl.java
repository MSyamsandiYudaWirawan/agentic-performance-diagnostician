package io.diag.eval.service.impl;

import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.entity.Target;
import io.diag.evidence.repository.IterationRepository;
import io.diag.evidence.repository.LoadReportRepository;
import io.diag.evidence.repository.RunRepository;
import io.diag.evidence.repository.TargetRepository;
import io.diag.evidence.service.TargetRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * DB-backed implementation of EvalScorer (Step 11 M0, scope §6.2, TigerStyle compliant).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EvalScorerImpl implements EvalScorer {

    private final RunRepository runRepository;
    private final IterationRepository iterationRepository;
    private final TargetRepository targetRepository;
    private final LoadReportRepository loadReportRepository;
    private final TargetRegistry targetRegistry;

    @Override
    public RunScore scoreRun(String runId) {
        Objects.requireNonNull(runId, "runId must not be null");

        Run run = runRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        String targetId = run.getTargetId();
        String groundTruth = resolveGroundTruthCategory(targetId);

        List<Iteration> iterations = iterationRepository.findByRunIdOrderByNAsc(runId);

        // Find final kept iteration, or fallback to last iteration (§6.2, D2)
        Iteration finalKept = null;
        for (Iteration iter : iterations) {
            if ("KEPT".equalsIgnoreCase(iter.getOutcome())) {
                finalKept = iter;
            }
        }

        String diagnosedCategory = "NONE";
        double finalP95 = run.getBaselineP95Ms() != null ? run.getBaselineP95Ms() : 0.0;
        double finalRps = run.getBaselineRps() != null ? run.getBaselineRps() : 0.0;

        if (finalKept != null) {
            if (finalKept.getHypothesis() != null && finalKept.getHypothesis().category() != null) {
                diagnosedCategory = finalKept.getHypothesis().category();
            }
            if (finalKept.getLoadReportId() != null) {
                Optional<LoadReport> lrOpt = loadReportRepository.findById(finalKept.getLoadReportId());
                if (lrOpt.isEmpty()) {
                    List<LoadReport> byRun = loadReportRepository.findByRunIdOrderByIdAsc(runId);
                    for (LoadReport r : byRun) {
                        if (finalKept.getLoadReportId().equals(r.getId())) {
                            lrOpt = Optional.of(r);
                            break;
                        }
                    }
                }
                if (lrOpt.isPresent() && lrOpt.get().getPayload() != null) {
                    LoadReport lr = lrOpt.get();
                    if (lr.getPayload().latency() != null) {
                        finalP95 = lr.getPayload().latency().p95();
                    }
                    finalRps = lr.getPayload().rps();
                }
            }
        } else if (!iterations.isEmpty()) {
            Iteration last = iterations.get(iterations.size() - 1);
            if (last.getHypothesis() != null && last.getHypothesis().category() != null) {
                diagnosedCategory = last.getHypothesis().category();
            }
        }

        boolean accurate = groundTruth.equalsIgnoreCase(diagnosedCategory);

        double baselineP95 = run.getBaselineP95Ms() != null ? run.getBaselineP95Ms() : 0.0;
        double noiseFloorMs = run.getNoiseFloorMs() != null ? run.getNoiseFloorMs() : 0.0;
        double p95Delta = baselineP95 - finalP95;
        boolean converged = (p95Delta > noiseFloorMs);

        double baselineRps = run.getBaselineRps() != null ? run.getBaselineRps() : 0.0;
        double noiseFloorRps = run.getNoiseFloorRps() != null ? run.getNoiseFloorRps() : 0.0;
        double rpsDelta = finalRps - baselineRps;
        boolean rpsImproved = (rpsDelta > noiseFloorRps);

        long tokensIn = run.getTokensIn() != null ? run.getTokensIn() : 0L;
        long tokensOut = run.getTokensOut() != null ? run.getTokensOut() : 0L;
        long totalTokens = tokensIn + tokensOut;
        BigDecimal totalCost = run.getCostUsd() != null ? run.getCostUsd() : BigDecimal.ZERO;

        return new RunScore(
                runId,
                targetId,
                run.getStatus(),
                groundTruth,
                diagnosedCategory,
                accurate,
                baselineP95,
                finalP95,
                p95Delta,
                noiseFloorMs,
                converged,
                baselineRps,
                finalRps,
                rpsDelta,
                noiseFloorRps,
                rpsImproved,
                iterations.size(),
                totalTokens,
                totalCost,
                run.getStartedAt(),
                run.getFinishedAt()
        );
    }

    @Override
    public MatrixScoreReport scoreRuns(List<String> runIds) {
        Objects.requireNonNull(runIds, "runIds must not be null");

        List<RunScore> scores = new ArrayList<>(runIds.size());
        int accurateCount = 0;
        int convergedCount = 0;

        for (String id : runIds) {
            RunScore score = scoreRun(id);
            scores.add(score);
            if (score.accurate()) {
                accurateCount++;
            }
            if (score.converged()) {
                convergedCount++;
            }
        }

        int total = scores.size();
        double accuracyRate = total > 0 ? (double) accurateCount / total : 0.0;
        double convergenceRate = total > 0 ? (double) convergedCount / total : 0.0;

        return new MatrixScoreReport(
                total,
                accurateCount,
                accuracyRate,
                convergedCount,
                convergenceRate,
                scores
        );
    }

    private String resolveGroundTruthCategory(String targetId) {
        if (targetId == null) {
            return "UNKNOWN";
        }
        if (targetRegistry != null) {
            Optional<Target> regOpt = targetRegistry.findById(targetId);
            if (regOpt.isPresent() && regOpt.get().getGroundTruthCategory() != null) {
                return regOpt.get().getGroundTruthCategory();
            }
        }
        Optional<Target> opt = targetRepository.findById(targetId);
        if (opt.isPresent() && opt.get().getGroundTruthCategory() != null) {
            return opt.get().getGroundTruthCategory();
        }
        if (targetRegistry != null) {
            for (Target canonical : targetRegistry.getCanonicalTargets()) {
                if (targetId.equalsIgnoreCase(canonical.getId())) {
                    return canonical.getGroundTruthCategory();
                }
            }
        }
        return "UNKNOWN";
    }
}
