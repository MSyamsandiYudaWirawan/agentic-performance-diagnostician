package io.diag.agent.loop;

import io.diag.agent.decision.DecisionDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;

import java.util.Objects;

public final class KeepRule {

    public static KeepDecision evaluate(DecisionDto d, LoadReportDto ref, LoadReportDto result, JfrReportDto prevJfr, JfrReportDto resultJfr, NoiseFloors f, double p95Bound) {
        // revJfr, resultJfr, p95Bound reserved for verified-mechanism keep — spec §10.5b, step 9

        Objects.requireNonNull(d, "decision must not be null");
        Objects.requireNonNull(ref, "ref must not be null");
        Objects.requireNonNull(result, "result must not be null");
        Objects.requireNonNull(f, "noiseFloors must not be null");

        double p95Improve = ref.latency().p95() - result.latency().p95();
        double p50Improve = ref.latency().p50() - result.latency().p50();
        double rpsImprove = result.rps() - ref.rps();


        boolean rpsImproved = rpsImprove > f.rpsFloor();
        boolean p95Improved = p95Improve > f.p95FloorMs();
        boolean p50Improved = p50Improve > f.p50FloorMs();
        boolean failGuard = result.failRate() <= ref.failRate();

        if ((rpsImproved || p95Improved) && failGuard) {
            // RPS takes priority when both fire — spec §10.5a classic keep
            String keepType = rpsImproved ? "RPS" : "P95";
            String reason = String.format("RPS delta %.1f (floor %.1f), P95 delta %.1fms (floor %.1fms)",
                    rpsImprove, f.rpsFloor(),
                    p95Improve, f.p95FloorMs());
            return new KeepDecision(true, keepType, reason);
        }

        if((rpsImproved || p95Improved) && !failGuard){
            return new KeepDecision(false,null,String.format("improved beyond floor but failRate worsened %.3f -> %.3f (guard)",
                    ref.failRate(),result.failRate()));
        }

        // skip if no JFR recordings provided
        if (prevJfr == null || resultJfr == null) {
            return new KeepDecision(false, null, String.format("RPS delta %.1f <= floor %.1f; P95 delta %.1fms <= floor %.1fms; failRate %.3f vs ref %.3f",
                    rpsImprove, f.rpsFloor(),
                    p95Improve, f.p95FloorMs(),
                    result.failRate(), ref.failRate()));
        }

        String predicted = d.prediction().mechanismSignalToEliminate();
        // todo add list valid signal to ai
        // 1. find signal
        SignalSummaryDto prevSignal = prevJfr.signals().get(predicted);
        SignalSummaryDto resultSignal = resultJfr.signals().get(predicted);
        if(prevSignal == null || resultSignal == null){
            return new KeepDecision(false, null,
                    String.format("mechanism signal '%s' not found in prevJfr signals %s",
                            predicted, prevJfr.signals().keySet()));
        }

        // 2. mechanism confirmed: reduction > 50% relative to prev recording (§10.32/§5.3b)
        long prevCount = prevSignal.count();
        long resultCount = resultSignal.count();

        // guard division by 0
        if (prevCount == 0) {
            return new KeepDecision(false, null,
                    String.format("mechanism '%s' has zero count in prevJfr — cannot compute reduction", predicted));
        }

        double reduction = (double) (prevCount - resultCount) / prevCount;
        if(reduction <= 0.5) {
            return new KeepDecision(false, null,
                    String.format("mechanism '%s' not eliminated: reduction %.2f <= 0.50 (prev=%d result=%d)",
                            predicted, reduction, prevCount, resultCount));
        }

        // 3. at least one metric improved beyond its floor
        if(!rpsImproved && !p95Improved && !p50Improved) {
            return new KeepDecision(false, null,
                    String.format("no metric cleared floor: RPS delta %.1f (floor %.1f), P95 delta %.1fms (floor %.1fms), P50 delta %.1fms (floor %.1fms)",
                            rpsImprove, f.rpsFloor(),
                            p95Improve, f.p95FloorMs(),
                            p50Improve, f.p50FloorMs()));
        }

        // 4. guards
        if(result.failRate() > ref.failRate()){
            return new KeepDecision(false, null,
                    String.format("failRate worsened %.3f -> %.3f (guard)", ref.failRate(), result.failRate()));
        }
        if(result.checkPassRate() < ref.checkPassRate()){
            return new KeepDecision(false, null,
                    String.format("checkPassRate worsened %.3f -> %.3f (guard)", ref.checkPassRate(), result.checkPassRate()));
        }

        // 5. tail bounded
        double p95Ceiling = ref.latency().p95() * (1+p95Bound);
        if(result.latency().p95() > p95Ceiling){
            return new KeepDecision(false, null,
                    String.format("p95 tail unbounded: %.1fms > ceiling %.1fms (ref %.1fms * (1 + %.2f))",
                            result.latency().p95(), p95Ceiling, ref.latency().p95(), p95Bound));
        }

        return new KeepDecision(true, "MECHANISM",
                String.format("mechanism '%s' eliminated (reduction %.2f), metric improved, guards passed",
                        predicted, reduction));



    }
}
