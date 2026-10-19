package io.diag.agent.memory.impl;

import io.diag.agent.memory.SymptomSummarizer;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SymptomSummarizerImpl implements SymptomSummarizer {

    private static final Set<String> NOTABLE = Set.of("CRITICAL", "CONCERNING");

    @Override
    public String summarize(LoadReportDto load, JfrReportDto jfr) {
        // Rule 1: load prefix — RPS, p95 rounded to int, error rate
        String prefix = String.format(Locale.ROOT, "Load: %.1f RPS, p95 %dms (%.1f%% errors). Hotspots: ",
                load.rps(), (long) load.latency().p95(), load.failRate());

        // Rule 2: prefer CRITICAL/CONCERNING signals; fall back to top-2 by count
        List<Map.Entry<String, SignalSummaryDto>> selected = jfr.signals().entrySet().stream()
                .filter(e -> NOTABLE.contains(e.getValue().severity().trim().toUpperCase(Locale.ROOT)))
                .toList();

        if (selected.isEmpty()) {
            // no notable severity — take top-2 by event count
            selected = jfr.signals().entrySet().stream()
                    .sorted(Comparator.comparingLong(e -> -e.getValue().count()))
                    .limit(2)
                    .toList();
        }

        // Rule 3: format each signal with up to 2 frames
        StringJoiner signals = new StringJoiner("; ");
        for (Map.Entry<String, SignalSummaryDto> entry : selected) {
            SignalSummaryDto s = entry.getValue();
            List<String> frames = s.topFrames().subList(0, Math.min(2, s.topFrames().size()));
            signals.add(String.format(Locale.ROOT, "%s (%s, count=%d, frames=[%s])",
                    entry.getKey(), s.severity(), s.count(), String.join(", ", frames)));
        }

        // Rule 4: Invariant <= 400 characters (truncated deterministically if necessary)
        String summary = prefix + signals;
        return summary.length() > 400 ? summary.substring(0, 400) : summary;
    }
}
