// target-runner/src/main/java/io/diag/runner/service/JfrCapture.java
package io.diag.runner.service;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.service.EvidenceService;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * Stops the target container (flushing JFR dumponexit), asserts the recording
 * landed on disk, and persists the artifact path + sha256 to the evidence store.
 * Payload is null here — Step 6 (JfrAnalyzer) fills it in.
 */
public class JfrCapture {
    private final TargetStack stack;
    private final EvidenceService evidenceService;
    private final String runId;

    public JfrCapture(TargetStack stack, EvidenceService evidenceService, String runId) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
    }

    /**
     * @param label  subdir label — baseline-1..3 / iter-<n> / smoke-<n>
     * @return persisted JfrReport row (payload null until Step 6)
     */
    public JfrReport capture(String label) throws IOException, InterruptedException {
        Objects.requireNonNull(label, "label must not be null");

        Path jfr = stack.stopAndHarvest(label);
        JfrReportDto dto = new JfrReportDto(runId, label, Map.of(), null);
        return evidenceService.createJfrReport(runId, label, dto, jfr);
    }
}
