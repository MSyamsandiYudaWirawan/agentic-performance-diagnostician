package io.diag.agent.loop;

import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrDiffDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.evidence.dto.SignalDeltaDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.dto.ThresholdsDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * M0, day 1 — the classic keep rule only (§10.5a). The MECHANISM branch
 * (spec §5.3b) comes after these are green: its tests will pass real JFR
 * DTOs instead of nulls.
 *
 * All fixtures are the REF-measured numbers from the spec's ground-truth
 * table — the same numbers the real S1 run will produce shapes of.
 *
 * Floors are FIXED constants here (spec: don't compute them in the test):
 *   rps floor 9.45 (5% of 189), p95 floor 125 ms, p50 floor 5 ms.
 */
class KeepRuleTest {

    // today's task needs exactly this much of the spec (§10.5a, classic):
    //
    //   keep iff (result.rps - ref.rps > rpsFloor)            // RPS branch, check first
    //        OR (ref.p95 - result.p95 > p95Floor)              // P95 branch
    //      AND result.failRate <= ref.failRate                  // guard
    //   keepType = "RPS" if the rps branch fired, else "P95".
    //   Otherwise keep=false, keepType=null, reason says why (with numbers).

    private static final NoiseFloors FLOORS = new NoiseFloors(125.0, 9.45, 5.0);

    // ------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------

    private static LoadReportDto load(double rps, double p95, double p50, double failRate) {
        return new LoadReportDto(
                "spring-petclinic", Instant.now().toString(),
                rps, 10_000,
                new LatencyDto(p50 * 2.2, p50, p95, p95 * 1.6, p95 * 4.0),
                failRate, 1.0 - failRate,
                new ThresholdsDto("FAIL", List.of("http_req_duration p(95)<500")));
    }

    private static DecisionDto decision() {
        return new DecisionDto(
                new HypothesisDto("H5", 0.8, "fat-jar classloader lock"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "72k monitor events"),
                new ChangeDto("template", null, "jar-unpack", Map.of()));
    }

    // ------------------------------------------------------------------
    // the classic rule, four cases
    // ------------------------------------------------------------------

    @Test
    void test1_saddleKeepsViaRps() {
        // REF jar-unpack-exp: rps 189→277 (+46%), p95 2500→3100 (+24%, worse!)
        // A p95-only rule reverts this. The RPS branch must keep it.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(277, 3100, 722, 0.0);

        var d = KeepRule.evaluate(decision(), ref, result, null, null, FLOORS, 0.50);

        assertThat(d.keep()).isTrue();
        assertThat(d.keepType()).isEqualTo("RPS");
    }

    @Test
    void test2_flatDeltasRevert() {
        // REF resource-cache-exp: everything inside the floors — falsified follow-up.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(191, 2470, 1040, 0.0);

        var d = KeepRule.evaluate(decision(), ref, result, null, null, FLOORS, 0.50);

        assertThat(d.keep()).isFalse();
        assertThat(d.keepType()).isNull();
        assertThat(d.reason()).isNotBlank();
    }

    @Test
    void test3_p95ImprovementAloneKeeps() {
        // rps flat (inside floor), p95 improves way beyond its floor.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(192, 1900, 1050, 0.0);

        var d = KeepRule.evaluate(decision(), ref, result, null, null, FLOORS, 0.50);

        assertThat(d.keep()).isTrue();
        assertThat(d.keepType()).isEqualTo("P95");
    }

    @Test
    void test4_failRateWorseBlocksKeep() {
        // rps improves beyond floor BUT failRate 0 → 0.02: fast because
        // writes stopped landing must never keep (§10.5a guard, §10.28).
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(250, 2400, 800, 0.02);

        var d = KeepRule.evaluate(decision(), ref, result, null, null, FLOORS, 0.50);

        assertThat(d.keep()).isFalse();
        assertThat(d.reason()).contains("failRate");
    }

    // ------------------------------------------------------------------
    // day 2 — the verified-mechanism branch (§10.5b). Your task needs
    // exactly this much of the spec:
    //
    //   checked AFTER the classic branch, only when classic did NOT keep:
    //   1. find the predicted signal: d.prediction().mechanismSignalToEliminate()
    //      — EXACT key in prevJfr.signals() only (D8: no fuzzy rescue); no
    //        match → branch fails, reason must name it and list valid keys
    //   2. mechanism confirmed: (prevCount - resultCount) / prevCount > 0.5
    //      (base = the PREVIOUS recording's count, per §10.32/§5.3b)
    //   3. at least one metric improved beyond its floor:
    //      rps ↑ beyond rpsFloor OR p95 ↓ beyond p95FloorMs OR p50 ↓ beyond p50FloorMs
    //   4. guards: result.failRate <= ref.failRate
    //             && result.checkPassRate >= ref.checkPassRate
    //   5. tail bounded: result.p95 <= ref.p95 * (1 + p95Bound)
    //   ALL five → keep, keepType "MECHANISM". Any miss → revert, reason says
    //   which condition failed, with numbers.
    // ------------------------------------------------------------------

    private static Map<String, SignalSummaryDto> signals(long monitorCount) {
        return Map.of(
                "JavaMonitorEnter", new SignalSummaryDto(monitorCount, 12.0, 407.0, 900.0, 1490.0,
                        "CRITICAL", List.of("jdk.internal.loader.UrlJarFiles$Cache")),
                "ThreadPark", new SignalSummaryDto(5_000, 100.0, 1000.0, 1200.0, 2000.0,
                        "CRITICAL", List.of("pool-wait")),
                "ExecutionSample", new SignalSummaryDto(9_000, null, null, null, null,
                        "N/A", List.of("business frame")));
    }

    private static JfrReportDto prevJfr(long monitorCount) {
        // the previous kept-state recording — diff is null on the base by definition
        return new JfrReportDto("run-1", "baseline-3", signals(monitorCount), null);
    }

    private static JfrReportDto resultJfr(long monitorCount, long prevMonitorCount) {
        return new JfrReportDto("run-1", "iter-1", signals(monitorCount),
                new JfrDiffDto("baseline-3", Map.of("JavaMonitorEnter",
                        new SignalDeltaDto(monitorCount - prevMonitorCount, null, null))));
    }

    private static DecisionDto decisionPredicting(String signal) {
        return new DecisionDto(
                new HypothesisDto("H5", 0.8, "fat-jar classloader lock"),
                new PredictionDto("rps", "improve", signal),
                new LedgerUpdateDto("H5", "strengthen", "72k monitor events"),
                new ChangeDto("template", null, "jar-unpack", Map.of()));
    }

    @Test
    void test5_pureSaddleKeepsViaMechanism() {
        // The case a p95-greedy loop gets WRONG: throughput/latency inside
        // noise, tail worse (+24%), but the predicted lock vanished (>50% drop)
        // and p50 improved beyond its floor → the S1 iteration-1 keep.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(192, 3100, 722, 0.0);   // rps +3 (floor 9.45), p95 +600, p50 −336

        var d = KeepRule.evaluate(decisionPredicting("JavaMonitorEnter"), ref, result,
                prevJfr(71_193), resultJfr(3_000, 71_193), FLOORS, 0.50);

        assertThat(d.keep()).isTrue();
        assertThat(d.keepType()).isEqualTo("MECHANISM");
    }

    @Test
    void test6_mechanismNotConfirmedReverts() {
        // resource-cache shape: the knob never touched the mechanism — signal
        // barely moved (−16%), metrics flat. "Plausible property, wrong circuit."
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(191, 2470, 1040, 0.0);

        var d = KeepRule.evaluate(decisionPredicting("JavaMonitorEnter"), ref, result,
                prevJfr(71_193), resultJfr(60_000, 71_193), FLOORS, 0.50);

        assertThat(d.keep()).isFalse();
        assertThat(d.keepType()).isNull();
    }

    @Test
    void test7_p95RegressionOverBoundReverts() {
        // mechanism confirmed, p50 improves, but the tail blew past the
        // regression bound (+60% > 50%) — the bound must bite.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(190, 4000, 800, 0.0);

        var d = KeepRule.evaluate(decisionPredicting("JavaMonitorEnter"), ref, result,
                prevJfr(71_193), resultJfr(3_000, 71_193), FLOORS, 0.50);

        assertThat(d.keep()).isFalse();
    }

    @Test
    void test8_unknownSignalFailsMechanismCleanly() {
        // hallucinated/misspelled signal name must fail the branch with a
        // reason naming it — never an exception, never a silent keep.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(190, 2450, 1050, 0.0);

        var d = KeepRule.evaluate(decisionPredicting("NotASignal"), ref, result,
                prevJfr(71_193), resultJfr(3_000, 71_193), FLOORS, 0.50);

        assertThat(d.keep()).isFalse();
        assertThat(d.reason()).contains("NotASignal");
    }

    @Test
    void test9_zeroPrevCountCannotBeConfirmed() {
        // A signal with ZERO events in the base recording cannot have "dropped".
        // Beware the trap: (0 - 0) / 0 in double math is NaN, and EVERY
        // comparison with NaN is false — including "NaN <= 0.5". The unguarded
        // division therefore CONFIRMS the mechanism. This test pins the guard.
        var ref    = load(189, 2500, 1058, 0.0);
        var result = load(190, 2500, 722, 0.0);   // rps +1, p95 flat; ONLY p50 clears a floor

        var d = KeepRule.evaluate(decisionPredicting("JavaMonitorEnter"), ref, result,
                prevJfr(0), resultJfr(0, 0), FLOORS, 0.50);

        assertThat(d.keep()).isFalse();
    }
}
