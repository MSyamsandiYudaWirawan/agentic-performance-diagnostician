package io.diag.agent.loop;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything the model sees for one decide turn (§5.6).
 * lastKeptJfr is null only on the very first decide turn when baseline-3 is not yet available —
 * in practice the loop always passes baseline-3 as the first value.
 * ledger and history are defensively copied — the loop mutates its own copies after construction.
 */
public record DecideContext(
        String targetName,
        int iteration,
        int maxIterations,
        LoadReportDto reference,
        NoiseFloors floors,
        JfrReportDto lastKeptJfr,
        Map<String, Double> ledger,
        List<HistoryEntry> history
) {
    public DecideContext {
        Objects.requireNonNull(targetName, "targetName must not be null");
        Objects.requireNonNull(reference,  "reference must not be null");
        Objects.requireNonNull(floors,     "floors must not be null");
        Objects.requireNonNull(ledger,     "ledger must not be null");
        Objects.requireNonNull(history,    "history must not be null");
        // Defensive copies — the loop advances its own state after handing context to the model.
        ledger  = Map.copyOf(ledger);
        history = List.copyOf(history);
    }
}
