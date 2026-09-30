package io.diag.eval.service.impl;

import io.diag.eval.model.RegressionKey;
import io.diag.eval.model.RegressionRow;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.RegressionTableService;
import io.diag.evidence.entity.Run;
import io.diag.evidence.repository.RunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Implementation of RegressionTableService (Step 11 M1, scope §10.20, TigerStyle compliant).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RegressionTableServiceImpl implements RegressionTableService {

    private final RunRepository runRepository;
    private final EvalScorer evalScorer;

    private record GroupCell(RegressionKey key, String targetId) {}

    @Override
    public List<RegressionRow> computeTable(List<String> runIds) {
        Objects.requireNonNull(runIds, "runIds must not be null");

        Map<GroupCell, List<RunScore>> groups = new LinkedHashMap<>();

        for (String runId : runIds) {
            Optional<Run> runOpt = runRepository.findById(runId);
            if (runOpt.isEmpty()) {
                continue;
            }
            Run run = runOpt.get();
            String promptHash = run.getPromptHash() != null ? run.getPromptHash() : "default";
            String model = run.getModel() != null ? run.getModel() : "unknown-model";
            String aggregator = run.getAggregatorVersion() != null ? run.getAggregatorVersion() : "1.0";
            RegressionKey key = new RegressionKey(promptHash, model, aggregator);

            String targetId = run.getTargetId() != null ? run.getTargetId() : "UNKNOWN";
            GroupCell cell = new GroupCell(key, targetId);

            RunScore score = evalScorer.scoreRun(runId);
            groups.computeIfAbsent(cell, k -> new ArrayList<>()).add(score);
        }

        List<RegressionRow> rows = new ArrayList<>(groups.size());

        for (Map.Entry<GroupCell, List<RunScore>> entry : groups.entrySet()) {
            GroupCell cell = entry.getKey();
            List<RunScore> scores = entry.getValue();
            int n = scores.size();
            if (n == 0) continue;

            int accurateCount = 0;
            int convergedCount = 0;
            double sumP95Delta = 0.0;
            double sumRpsDelta = 0.0;
            int sumIters = 0;
            long sumTokens = 0L;
            BigDecimal sumCost = BigDecimal.ZERO;

            for (RunScore s : scores) {
                if (s.accurate()) accurateCount++;
                if (s.converged()) convergedCount++;
                sumP95Delta += s.p95DeltaMs();
                sumRpsDelta += s.rpsDelta();
                sumIters += s.iterationsUsed();
                sumTokens += s.totalTokens();
                sumCost = sumCost.add(s.totalCostUsd());
            }

            double accuracyRate = (double) accurateCount / n;
            double convergenceRate = (double) convergedCount / n;
            double meanP95Delta = sumP95Delta / n;
            double meanRpsDelta = sumRpsDelta / n;
            double meanIters = (double) sumIters / n;
            long meanTokens = sumTokens / n;
            BigDecimal meanCost = sumCost.divide(BigDecimal.valueOf(n), 4, RoundingMode.HALF_UP);

            rows.add(new RegressionRow(
                    cell.key(),
                    cell.targetId(),
                    n,
                    accuracyRate,
                    convergenceRate,
                    meanP95Delta,
                    meanRpsDelta,
                    meanIters,
                    meanTokens,
                    meanCost
            ));
        }

        return rows;
    }

    @Override
    public String renderMarkdown(List<RegressionRow> rows) {
        Objects.requireNonNull(rows, "rows must not be null");

        StringBuilder sb = new StringBuilder();
        sb.append("| Prompt Hash | Model | Aggregator | Target | Runs | Accuracy | Converged | Mean p95 Δ | Mean RPS Δ | Mean Iters | Mean Tokens | Mean Cost |\n");
        sb.append("|:---|:---|:---|:---|:---|:---|:---|:---|:---|:---|:---|:---|\n");

        for (RegressionRow r : rows) {
            String shortHash = r.key().promptHash().length() > 8
                    ? r.key().promptHash().substring(0, 8)
                    : r.key().promptHash();

            sb.append(String.format(Locale.ROOT,
                    "| `%s` | %s | %s | %s | %d | %.1f%% | %.1f%% | %+.1f ms | %+.1f | %.1f | %d | $%.4f |\n",
                    shortHash,
                    r.key().model(),
                    r.key().aggregatorVersion(),
                    r.targetId(),
                    r.runCount(),
                    r.accuracyRate() * 100,
                    r.convergenceRate() * 100,
                    r.meanP95DeltaMs(),
                    r.meanRpsDelta(),
                    r.meanIterations(),
                    r.meanTokens(),
                    r.meanCostUsd().doubleValue()
            ));
        }

        return sb.toString();
    }
}
