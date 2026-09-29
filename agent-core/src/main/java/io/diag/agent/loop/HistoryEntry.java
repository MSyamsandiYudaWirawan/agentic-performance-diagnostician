package io.diag.agent.loop;

import java.util.Objects;

/**
 * One past iteration's summary, fed to the model as context (§5.6).
 * rps and p95 are null on WASTED rows — nothing was measured.
 * note carries the rejection reason, build-log tail, or smoke detail.
 */
public record HistoryEntry(
        int n,
        String hypothesisCategory,
        double confidence,
        String changeDescription,
        String outcome,      // KEPT | REVERTED | WASTED
        String keepType,     // RPS | P95 | MECHANISM | null
        Double rps,          // null on WASTED
        Double p95,          // null on WASTED
        String note
) {
    public HistoryEntry {
        if (n <= 0) throw new IllegalArgumentException("n must be > 0, got " + n);
        Objects.requireNonNull(outcome, "outcome must not be null");
    }
}
