package io.diag.agent.loop;

import io.diag.agent.decision.DecisionDto;
import io.diag.agent.decision.LedgerUpdateDto;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.PredictionDto;
import io.diag.evidence.dto.ThresholdsDto;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * M0, final piece — Baseline.of(three): turning three noisy baseline runs
 * into the reference + noise floors the keep rule compares against (§10.5).
 *
 * Fixtures for tests 1–2 are THIS machine's measured step-4 numbers
 * (rps 124.7/127.7 consecutive baselines, ~2.4% spread) — the fact that the
 * 5% term dominates the spread term is a recorded calibration fact (D17),
 * now executable.
 *
 * Your task needs exactly this much math:
 *
 *   Baseline.of(List<LoadReportDto> three):
 *     require exactly 3 runs (IllegalArgumentException otherwise)
 *
 *   reference = the HARDEST-to-beat composite, per field across the 3 runs:
 *     rps = max   |  p95 = min  |  p50 = min
 *     failRate = min  |  checkPassRate = max
 *     (fields KeepRule never compares — repo, dateUtc, totalRequests,
 *      thresholds — just take the FIRST run's values)
 *
 *   floors, per metric = max(5% of MEDIAN of the 3, max - min of the 3),
 *   in the metric's own units:
 *     rpsFloor  from the 3 rps values
 *     p95FloorMs from the 3 p95 values
 *     p50FloorMs from the 3 p50 values
 *   (median of three = sort, take the middle one)
 */
class BaselineTest {

    private static LoadReportDto load(double rps, double p95, double p50, double failRate, double checkPassRate) {
        return new LoadReportDto(
                "spring-petclinic", Instant.now().toString(),
                rps, 10_000,
                new LatencyDto(p50 * 2.2, p50, p95, p95 * 1.6, p95 * 4.0),
                failRate, checkPassRate,
                new ThresholdsDto("FAIL", List.of("http_req_duration p(95)<500")));
    }

    // this machine, step-4 shape: ~2% spread — the 5% term must win
    private static List<LoadReportDto> calmMachine() {
        return List.of(
                load(124.7, 4463, 940, 0.002, 0.998),
                load(127.7, 4380, 960, 0.000, 1.000),
                load(126.4, 4421, 950, 0.001, 0.999));
    }

    @Test
    void test1_referenceIsTheHardestToBeatComposite() {
        var b = Baseline.of(calmMachine());

        // rps = max of the three; latency = min of the three
        assertThat(b.reference().rps()).isEqualTo(127.7);
        assertThat(b.reference().latency().p95()).isEqualTo(4380);
        assertThat(b.reference().latency().p50()).isEqualTo(940);
        // guards also take the hard side
        assertThat(b.reference().failRate()).isEqualTo(0.0);
        assertThat(b.reference().checkPassRate()).isEqualTo(1.0);
    }

    @Test
    void test2_fivePercentDominatesWhenSpreadIsSmall() {
        var b = Baseline.of(calmMachine());

        // rps: median 126.4 -> 5% = 6.32; spread 127.7-124.7 = 3.0 -> floor 6.32
        assertThat(b.floors().rpsFloor()).isCloseTo(6.32, within(1e-9));
        // p95: median 4421 -> 5% = 221.05; spread 4463-4380 = 83 -> floor 221.05
        assertThat(b.floors().p95FloorMs()).isCloseTo(221.05, within(1e-9));
        // p50: median 950 -> 5% = 47.5; spread 960-940 = 20 -> floor 47.5
        assertThat(b.floors().p50FloorMs()).isCloseTo(47.5, within(1e-9));
    }

    @Test
    void test3_spreadDominatesWhenRunsDiverge() {
        // a machine having a bad day: rps 100/130/110, p95 2000/3000/2400
        var b = Baseline.of(List.of(
                load(100, 2000, 700, 0.0, 1.0),
                load(130, 3000, 1000, 0.0, 1.0),
                load(110, 2400, 800, 0.0, 1.0)));

        // rps: 5% of median 110 = 5.5, spread 30 -> floor 30
        assertThat(b.floors().rpsFloor()).isCloseTo(30.0, within(1e-9));
        // p95: 5% of median 2400 = 120, spread 1000 -> floor 1000
        assertThat(b.floors().p95FloorMs()).isCloseTo(1000.0, within(1e-9));
        // and the reference is still best-of
        assertThat(b.reference().rps()).isEqualTo(130);
        assertThat(b.reference().latency().p95()).isEqualTo(2000);
    }

    @Test
    void test4_floorsActuallyGateTheKeepRule() {
        // the point of it all: Baseline feeds KeepRule. A 5%-ish rps gain
        // must NOT keep; a 10% gain must.
        var b = Baseline.of(calmMachine());

        var justUnder = load(134.0, 4380, 940, 0.0, 1.0);  // rps +6.3 < floor 6.32
        var wellOver  = load(140.6, 4380, 940, 0.0, 1.0);  // rps +12.9 > floor 6.32

        var d1 = KeepRule.evaluate(decision(), b.reference(), justUnder, null, null, b.floors(), 0.50);
        var d2 = KeepRule.evaluate(decision(), b.reference(), wellOver,  null, null, b.floors(), 0.50);

        assertThat(d1.keep()).isFalse();
        assertThat(d2.keep()).isTrue();
        assertThat(d2.keepType()).isEqualTo("RPS");
    }

    @Test
    void test5_requiresExactlyThreeRuns() {
        assertThatThrownBy(() -> Baseline.of(List.of(load(1, 1, 1, 0, 1))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Baseline.of(List.of(
                load(1, 1, 1, 0, 1), load(2, 2, 2, 0, 1), load(3, 3, 3, 0, 1), load(4, 4, 4, 0, 1))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static DecisionDto decision() {
        return new DecisionDto(
                new HypothesisDto("H5", 0.8, "fat-jar classloader lock"),
                new PredictionDto("rps", "improve", "JavaMonitorEnter"),
                new LedgerUpdateDto("H5", "strengthen", "72k monitor events"),
                new ChangeDto("template", null, "jar-unpack", Map.of()));
    }
}
