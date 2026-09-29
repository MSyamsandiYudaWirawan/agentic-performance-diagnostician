package io.diag.agent.loop;

import io.diag.agent.config.GenParams;
import io.diag.agent.decision.DecisionDto;
import io.diag.evidence.RunStatus;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.service.EvidenceService;
import io.diag.runner.config.ChangeResult;
import io.diag.runner.service.BuildFailedException;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.TemplateNotAdmittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class AgentLoop {

    private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);
    private static final double LEDGER_STEP = 0.2;

    private final EvidenceService evidenceService;
    private final ChangeApplier   changeApplier;
    private final DecideTurn      decideTurn;
    private final TargetPipeline  targetPipeline;
    private final LoopConfig      loopConfig;
    private final GenParams       genParams;
    private final Path            targetRepo;
    private final String          targetId;

    // run-scoped mutable state — reset by initRunState()
    private String              runId;
    private String              lastKeptSha;
    private Baseline            baseline;
    private final Map<String, Double> ledger  = new LinkedHashMap<>();
    private final List<HistoryEntry>  history = new ArrayList<>();
    private JfrReportDto        lastKeptJfr;
    private long                tokensIn;
    private long                tokensOut;
    private BigDecimal          costUsd;
    private Instant             startWall;

    public AgentLoop(EvidenceService evidenceService, ChangeApplier changeApplier,
                     DecideTurn decideTurn, TargetPipeline targetPipeline,
                     LoopConfig loopConfig, GenParams genParams,
                     Path targetRepo, String targetId) {
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService");
        this.changeApplier   = Objects.requireNonNull(changeApplier,   "changeApplier");
        this.decideTurn      = Objects.requireNonNull(decideTurn,      "decideTurn");
        this.targetPipeline  = Objects.requireNonNull(targetPipeline,  "targetPipeline");
        this.loopConfig      = Objects.requireNonNull(loopConfig,      "loopConfig");
        this.genParams       = Objects.requireNonNull(genParams,       "genParams");
        this.targetRepo      = Objects.requireNonNull(targetRepo,      "targetRepo");
        this.targetId        = Objects.requireNonNull(targetId,        "targetId");
    }

    // -------------------------------------------------------------------------
    // Public entries
    // -------------------------------------------------------------------------

    public String start() throws Exception {
        initRunState();
        String originSha = changeApplier.currentSha(targetRepo);
        Run run = evidenceService.createRun(
                targetId,
                genParams.provider(), genParams.model(),
                SystemPrompts.promptHash(), "1.0",
                genParams.toMap());
        runId       = run.getId();
        lastKeptSha = originSha;
        return runLoop(originSha);
    }

    /**
     * Starts the loop against an already-created run (e.g. constructed run-scoped like the Step 8 gate).
     *
     * @param preCreatedRunId the runId already recorded in EvidenceService
     * @return the active runId
     * @throws Exception if baseline or iteration fails
     */
    public String start(String preCreatedRunId) throws Exception {
        Objects.requireNonNull(preCreatedRunId, "preCreatedRunId must not be null");
        initRunState();
        runId       = preCreatedRunId;
        String originSha = changeApplier.currentSha(targetRepo);
        lastKeptSha = originSha;
        return runLoop(originSha);
    }

    private String runLoop(String originSha) throws Exception {
        try {
            baselinePhase(originSha);
            iterate(1);
            finish(RunStatus.COMPLETED);
        } catch (GuardrailAbortedException e) {
            finish(RunStatus.ABORTED);
        } catch (InfraFailureException e) {
            finish(RunStatus.INCOMPLETE);
        } catch (Exception e) {
            finish(RunStatus.INCOMPLETE);
            throw e;
        }
        return runId;
    }

    public String resume(String resumeRunId) throws Exception {
        Objects.requireNonNull(resumeRunId, "resumeRunId must not be null");
        initRunState();
        runId = resumeRunId;

        Run run = evidenceService.findRun(resumeRunId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + resumeRunId));

        boolean resumeAborted = "true".equalsIgnoreCase(System.getenv("DIAG_RESUME_ABORTED"));
        boolean statusOk = RunStatus.RUNNING.name().equals(run.getStatus())
                || (resumeAborted && RunStatus.ABORTED.name().equals(run.getStatus()));
        if (!statusOk) {
            throw new IllegalStateException(
                    "run " + resumeRunId + " is not resumable (status=" + run.getStatus() + ")");
        }

        // single-flight: no OTHER running row
        evidenceService.findRunningRun().ifPresent(other -> {
            if (!other.getId().equals(resumeRunId)) {
                throw new IllegalStateException("another run is already RUNNING: " + other.getId());
            }
        });

        lastKeptSha = run.getLastKeptSha() != null ? run.getLastKeptSha() : run.getOriginSha();

        // revert-if-dirty (§10.25)
        String currentSha = changeApplier.currentSha(targetRepo);
        if (!currentSha.equals(lastKeptSha)) {
            changeApplier.revertTo(targetRepo, lastKeptSha);
        }

        List<Iteration> existing = evidenceService.findIterations(runId);

        if (run.getBaselineP95Ms() == null) {
            // died during baselining — redo
            baselinePhase(run.getOriginSha());
        } else {
            // reload baseline from the three persisted load reports (§9 p50-floor resume gap)
            List<LoadReport> allReports = evidenceService.findLoadReports(runId);
            List<LoadReportDto> baselineDtos = new ArrayList<>();
            for (LoadReport lr : allReports) {
                String lbl = lr.getLabel();
                if ("baseline-1".equals(lbl) || "baseline-2".equals(lbl) || "baseline-3".equals(lbl)) {
                    baselineDtos.add(lr.getPayload());
                }
            }
            baseline = Baseline.of(baselineDtos);

            // lastKeptJfr: latest KEPT iteration's jfr, else baseline-3
            String lastKeptLabel = "baseline-3";
            for (Iteration it : existing) {
                if ("KEPT".equals(it.getOutcome())) {
                    lastKeptLabel = "iter-" + it.getN();
                }
            }
            lastKeptJfr = evidenceService.findJfrReport(runId, lastKeptLabel)
                    .map(JfrReport::getPayload)
                    .orElse(null);

            // rebuild ledger and history from existing rows
            for (Iteration it : existing) {
                if (it.getLedger() != null) {
                    for (Map.Entry<String, Object> e : it.getLedger().entrySet()) {
                        if (e.getValue() instanceof Number n) {
                            ledger.put(e.getKey(), n.doubleValue());
                        }
                    }
                }
                history.add(new HistoryEntry(
                        it.getN(),
                        it.getHypothesis() != null ? it.getHypothesis().category() : "?",
                        it.getHypothesis() != null ? it.getHypothesis().confidence() : 0.0,
                        it.getChange() != null ? it.getChange().kind() : "?",
                        it.getOutcome(),
                        it.getKeepType(),
                        null, null,
                        it.getFinding()));
            }
        }

        int startN = existing.stream().mapToInt(Iteration::getN).max().orElse(0) + 1;

        try {
            iterate(startN);
            finish(RunStatus.COMPLETED);
        } catch (GuardrailAbortedException e) {
            finish(RunStatus.ABORTED);
        } catch (InfraFailureException e) {
            finish(RunStatus.INCOMPLETE);
        } catch (Exception e) {
            finish(RunStatus.INCOMPLETE);
            throw e;
        }
        return runId;
    }

    // -------------------------------------------------------------------------
    // Phases
    // -------------------------------------------------------------------------

    private void baselinePhase(String originSha) throws Exception {
        targetPipeline.rebuild();
        List<LoadReportDto> loads = new ArrayList<>(3);
        JfrReportDto b3Jfr = null;
        for (int k = 1; k <= 3; k++) {
            // Baselines pass previous=null per spec §2 (line 134) — noise floor of pristine codebase
            BenchmarkCycle cycle = targetPipeline.benchmark("baseline-" + k, null);
            loads.add(cycle.load());
            if (k == 3) {
                b3Jfr = cycle.jfr();
            }
        }
        baseline    = Baseline.of(loads);
        lastKeptJfr = b3Jfr;
        NoiseFloors f = baseline.floors();
        evidenceService.recordBaseline(runId,
                baseline.reference().latency().p95(), f.p95FloorMs(),
                baseline.reference().rps(), f.rpsFloor(),
                originSha);
    }

    private void iterate(int startN) throws Exception {
        for (int n = startN; n <= loopConfig.maxIterations(); n++) {
            checkGuardrails();

            DecisionDto dec = decidePhase(n);
            if (dec == null) {
                log.warn("Decision invalid at iteration {} — stopping loop early to avoid token waste", n);
                break;
            }

            checkGuardrails();

            ChangeResult changeResult = applyPhase(n, dec);
            if (changeResult == null) continue;  // WASTED — rejection

            boolean verified = verifyPhase(n, dec, changeResult);
            if (!verified) continue;  // REVERTED

            measureAndJudgePhase(n, dec, changeResult);

            checkGuardrails();
        }
    }

    private DecisionDto decidePhase(int n) {
        DecideContext ctx = new DecideContext(
                targetId, n, loopConfig.maxIterations(),
                baseline.reference(), baseline.floors(),
                lastKeptJfr,
                Map.copyOf(ledger),
                List.copyOf(history));

        DecideTurn.DecideResult result = decideTurn.decide(n, ctx);
        tokensIn  += result.tokensIn();
        tokensOut += result.tokensOut();
        costUsd    = costUsd.add(computeCost(result.tokensIn(), result.tokensOut()));

        if (result.infraFailure()) {
            log.warn("LLM infra failure at iteration {}: {}", n, result.error());
            throw new InfraFailureException(result.error());
        }

        if (result.decision() == null) {
            log.warn("Decision invalid after retry at iteration {}: {}", n, result.error());
            evidenceService.createIteration(runId, n, null, null, null,
                    "WASTED", lastKeptSha, null, null, null, null,
                    "invalid decision: " + result.error());
            history.add(new HistoryEntry(n, "?", 0.0, "invalid-decision",
                    "WASTED", null, null, null, result.error()));
            return null;
        }

        // update ledger: fixed ±0.2 step clamped to [0.0, 1.0]
        String cat     = result.decision().ledger().category();
        String dir     = result.decision().ledger().direction();
        double current = ledger.getOrDefault(cat, 0.0);
        double updated = "strengthen".equals(dir)
                ? Math.min(1.0, current + LEDGER_STEP)
                : Math.max(0.0, current - LEDGER_STEP);
        ledger.put(cat, updated);

        return result.decision();
    }

    private ChangeResult applyPhase(int n, DecisionDto dec) {
        try {
            return changeApplier.apply(targetRepo, dec.change(),
                    "iter-" + n + ": " + dec.hypothesis().rationale());
        } catch (IllegalArgumentException | TemplateNotAdmittedException e) {
            log.warn("Change rejected at iteration {}: {}", n, e.getMessage());
            evidenceService.createIteration(runId, n, dec.hypothesis(),
                    Map.copyOf(ledger), dec.change(),
                    "WASTED", lastKeptSha, null, null, null, null,
                    "rejected: " + e.getMessage());
            history.add(new HistoryEntry(n,
                    dec.hypothesis().category(), dec.hypothesis().confidence(),
                    dec.change().kind(), "WASTED", null, null, null, e.getMessage()));
            return null;
        } catch (Exception e) {
            throw new InfraFailureException("apply failed: " + e.getMessage());
        }
    }

    private boolean verifyPhase(int n, DecisionDto dec, ChangeResult changeResult) throws Exception {
        try {
            targetPipeline.rebuild();
        } catch (BuildFailedException e) {
            revertAndRecord(n, dec, changeResult, "build failed: " + e.getMessage());
            return false;
        }

        if (changeResult.javaTouched()) {
            boolean passed;
            try {
                passed = targetPipeline.runTests();
            } catch (Exception e) {
                revertAndRecord(n, dec, changeResult, "tests threw: " + e.getMessage());
                return false;
            }
            if (!passed) {
                revertAndRecord(n, dec, changeResult, "tests failed");
                return false;
            }
        }

        SmokeResult smoke;
        try {
            smoke = targetPipeline.smoke(n);
        } catch (Exception e) {
            revertAndRecord(n, dec, changeResult, "smoke threw: " + e.getMessage());
            return false;
        }
        if (!smoke.pass()) {
            revertAndRecord(n, dec, changeResult, "smoke: " + smoke.detail());
            return false;
        }

        return true;
    }

    private void measureAndJudgePhase(int n, DecisionDto dec, ChangeResult changeResult) throws Exception {
        BenchmarkCycle cycle;
        try {
            cycle = targetPipeline.benchmark("iter-" + n, lastKeptJfr);
        } catch (Exception e) {
            revertAndRecord(n, dec, changeResult, "benchmark infra: " + e.getMessage());
            throw new InfraFailureException("benchmark failed: " + e.getMessage());
        }

        KeepDecision kd = KeepRule.evaluate(
                dec, baseline.reference(), cycle.load(),
                lastKeptJfr, cycle.jfr(),
                baseline.floors(), loopConfig.keepP95RegressionBound());

        String finding = buildFinding(dec, baseline.reference(), cycle, kd);

        if (kd.keep()) {
            lastKeptSha = changeResult.commitSha();
            // reference advances on keep; floors do NOT recompute (§6)
            baseline    = new Baseline(cycle.load(), baseline.floors());
            lastKeptJfr = cycle.jfr();
            evidenceService.recordKeptSha(runId, lastKeptSha);
            evidenceService.createIteration(runId, n, dec.hypothesis(),
                    Map.copyOf(ledger), dec.change(),
                    "KEPT", lastKeptSha,
                    cycle.loadReportId(), cycle.jfrReportId(),
                    changeResult.filesTouched().files(), kd.keepType(), finding);
            history.add(new HistoryEntry(n,
                    dec.hypothesis().category(), dec.hypothesis().confidence(),
                    dec.change().kind(), "KEPT", kd.keepType(),
                    cycle.load().rps(), cycle.load().latency().p95(), finding));
        } else {
            changeApplier.revertTo(targetRepo, lastKeptSha);
            evidenceService.createIteration(runId, n, dec.hypothesis(),
                    Map.copyOf(ledger), dec.change(),
                    "REVERTED", lastKeptSha,
                    cycle.loadReportId(), cycle.jfrReportId(),
                    changeResult.filesTouched().files(), null, finding);
            history.add(new HistoryEntry(n,
                    dec.hypothesis().category(), dec.hypothesis().confidence(),
                    dec.change().kind(), "REVERTED", null,
                    cycle.load().rps(), cycle.load().latency().p95(), finding));
        }
    }

    // -------------------------------------------------------------------------
    // Guardrails (§10.25)
    // -------------------------------------------------------------------------

    private void checkGuardrails() {
        long wallMs = Duration.between(startWall, Instant.now()).toMillis();
        if (wallMs > loopConfig.maxWallMs()) {
            log.warn("Guardrail: wall-clock {}ms > cap {}ms", wallMs, loopConfig.maxWallMs());
            throw new GuardrailAbortedException("wall-clock cap (" + loopConfig.maxWallMs() + "ms) exceeded: " + wallMs + "ms");
        }
        if (tokensIn + tokensOut > loopConfig.maxTokens()) {
            log.warn("Guardrail: tokens {} > cap {}", tokensIn + tokensOut, loopConfig.maxTokens());
            throw new GuardrailAbortedException("token cap (" + loopConfig.maxTokens() + ") exceeded: " + (tokensIn + tokensOut));
        }
        if (loopConfig.maxCostUsd().compareTo(BigDecimal.ZERO) > 0
                && costUsd.compareTo(loopConfig.maxCostUsd()) > 0) {
            log.warn("Guardrail: cost {} > cap {}", costUsd, loopConfig.maxCostUsd());
            throw new GuardrailAbortedException("cost cap ($" + loopConfig.maxCostUsd() + ") exceeded: $" + costUsd);
        }
    }

    // -------------------------------------------------------------------------
    // Single exit funnel (§5.9) — every path ends here
    // -------------------------------------------------------------------------

    private void finish(RunStatus targetStatus) {
        // revert-if-dirty (§10.25): no change survives unbenchmarked
        try {
            String current = changeApplier.currentSha(targetRepo);
            if (!current.equals(lastKeptSha)) {
                changeApplier.revertTo(targetRepo, lastKeptSha);
            }
        } catch (Exception e) {
            log.error("finish: revert-if-dirty failed", e);
        }
        try {
            evidenceService.transitionRunStatus(runId, RunStatus.RUNNING, targetStatus);
        } catch (IllegalStateException e) {
            // Already transitioned or idempotent finish
            log.info("finish: run {} status transition to {} skipped ({})", runId, targetStatus, e.getMessage());
        } catch (Exception e) {
            log.error("finish: status transition to {} failed", targetStatus, e);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void initRunState() {
        tokensIn  = 0L;
        tokensOut = 0L;
        costUsd   = BigDecimal.ZERO;
        startWall = Instant.now();
        ledger.clear();
        history.clear();
        for (int i = 1; i <= 7; i++) ledger.put("H" + i, 0.0);
    }

    private void revertAndRecord(int n, DecisionDto dec, ChangeResult changeResult, String note) {
        try {
            changeApplier.revertTo(targetRepo, lastKeptSha);
        } catch (Exception e) {
            log.error("revert failed at iteration {}: {}", n, e.getMessage());
        }
        List<io.diag.evidence.dto.FilesTouchedDto> files =
                (changeResult != null && changeResult.filesTouched() != null)
                        ? changeResult.filesTouched().files()
                        : List.of();
        evidenceService.createIteration(runId, n, dec.hypothesis(),
                Map.copyOf(ledger), dec.change(),
                "REVERTED", lastKeptSha, null, null,
                files, null, note);
        history.add(new HistoryEntry(n,
                dec.hypothesis().category(), dec.hypothesis().confidence(),
                dec.change().kind(), "REVERTED", null, null, null, note));
    }

    private BigDecimal computeCost(long in, long out) {
        if (loopConfig.priceInPerMtok().compareTo(BigDecimal.ZERO) == 0
                && loopConfig.priceOutPerMtok().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal inCost  = loopConfig.priceInPerMtok()
                .multiply(BigDecimal.valueOf(in))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
        BigDecimal outCost = loopConfig.priceOutPerMtok()
                .multiply(BigDecimal.valueOf(out))
                .divide(BigDecimal.valueOf(1_000_000), 10, RoundingMode.HALF_UP);
        return inCost.add(outCost);
    }

    private String buildFinding(DecisionDto dec, LoadReportDto ref,
                                 BenchmarkCycle cycle, KeepDecision kd) {
        String signal = dec.prediction().mechanismSignalToEliminate();
        long prevCount   = 0;
        long resultCount = 0;
        if (lastKeptJfr != null && lastKeptJfr.signals() != null
                && lastKeptJfr.signals().containsKey(signal)) {
            prevCount = lastKeptJfr.signals().get(signal).count();
        }
        if (cycle.jfr().signals() != null && cycle.jfr().signals().containsKey(signal)) {
            resultCount = cycle.jfr().signals().get(signal).count();
        }
        double signalPct = prevCount > 0 ? (100.0 * (resultCount - prevCount) / prevCount) : 0.0;
        double rpsDelta  = cycle.load().rps() - ref.rps();
        double p95Delta  = cycle.load().latency().p95() - ref.latency().p95();
        double rpsPct    = ref.rps() > 0 ? (100.0 * rpsDelta / ref.rps()) : 0.0;
        double p95Pct    = ref.latency().p95() > 0 ? (100.0 * p95Delta / ref.latency().p95()) : 0.0;
        return String.format("%s %d\u2192%d (%+.0f%%); rps %.0f\u2192%.0f (%+.0f%%); p95 %.0f\u2192%.0f (%+.0f%%); keep=%s",
                signal, prevCount, resultCount, signalPct,
                ref.rps(), cycle.load().rps(), rpsPct,
                ref.latency().p95(), cycle.load().latency().p95(), p95Pct,
                kd.keep() ? kd.keepType() : "NO");
    }

    // -------------------------------------------------------------------------
    // Private sentinel — signals infra failure up through iterate() to start/resume
    // -------------------------------------------------------------------------

    static final class InfraFailureException extends RuntimeException {
        InfraFailureException(String message) { super(message); }
    }

    static final class GuardrailAbortedException extends RuntimeException {
        GuardrailAbortedException(String message) { super(message); }
    }
}
