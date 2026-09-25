package io.diag.agent.loop;

import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public final class Baseline {
    private final LoadReportDto reference;
    private final NoiseFloors floors;

    public Baseline(LoadReportDto reference, NoiseFloors floors) {
        this.reference = Objects.requireNonNull(reference, "reference must not be null");
        this.floors = Objects.requireNonNull(floors, "floors must not be null");
    }

    public static Baseline of(List<LoadReportDto> runs) {
        Objects.requireNonNull(runs, "runs must not be null");
        if (runs.size() != 3) {
            throw new IllegalArgumentException("exactly 3 baseline runs required, got " + runs.size());
        }
        LoadReportDto r0 = runs.get(0);
        LoadReportDto r1 = runs.get(1);
        LoadReportDto r2 = runs.get(2);

        // reference: hardest-to-beat composite
        double refRps = max(r0.rps(), r1.rps(), r2.rps());
        double refP95 = min(r0.latency().p95(), r1.latency().p95(), r2.latency().p95());
        double refP50 = min(r0.latency().p50(), r1.latency().p50(), r2.latency().p50());
        double refP99 = min(r0.latency().p99(), r1.latency().p99(), r2.latency().p99());
        double refFailRate = min(r0.failRate(), r1.failRate(), r2.failRate());
        double refCheckPassRate = max(r0.checkPassRate(), r1.checkPassRate(), r2.checkPassRate());
        double refAvg = min(r0.latency().avg(), r1.latency().avg(), r2.latency().avg());
        double refMax = min(r0.latency().max(), r1.latency().max(), r2.latency().max());


        LoadReportDto reference = new LoadReportDto(
                r0.repo(),r0.dateUtc(),
                refRps,r0.totalRequests(),
                new LatencyDto(refAvg,refP50,refP95,refP99,refMax),
                refFailRate,refCheckPassRate,
                r0.thresholds()
        );

        // floors: max(5% of median, spread)
        double rpsFloor  = floor(r0.rps(), r1.rps(), r2.rps());
        double p95Floor  = floor(r0.latency().p95(), r1.latency().p95(), r2.latency().p95());
        double p50Floor  = floor(r0.latency().p50(), r1.latency().p50(), r2.latency().p50());

        return new Baseline(reference, new NoiseFloors(p95Floor, rpsFloor, p50Floor));


    }
    private static double floor(double a, double b, double c) {
        double median = median(a, b, c);
        double spread = max(a, b, c) - min(a, b, c);
        return Math.max(0.05 * median, spread);
    }

    private static double median(double a, double b, double c) {
        double[] v = {a, b, c};
        Arrays.sort(v);
        return v[1];
    }

    private static double min(double a, double b, double c) {
        return Math.min(Math.min(a, b), c);
    }

    private static double max(double a, double b, double c) {
        return Math.max(Math.max(a, b), c);
    }

    public LoadReportDto reference() {
        return reference;
    }

    public NoiseFloors floors() {
        return floors;
    }
}
