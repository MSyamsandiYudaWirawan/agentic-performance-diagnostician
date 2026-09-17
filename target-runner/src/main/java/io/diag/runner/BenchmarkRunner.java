package io.diag.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.ThresholdsDto;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.service.EvidenceService;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

public final class BenchmarkRunner {
    private final TargetStack stack;
    private final EvidenceService evidenceService;
    private final EnvelopeGuard envelopeGuard;
    private final ObjectMapper mapper;
    private final Path evidenceRoot;
    private final String runId;
    private final String targetName;


    public BenchmarkRunner(TargetStack stack, EvidenceService evidenceService, EnvelopeGuard envelopeGuard,
                           ObjectMapper mapper, Path evidenceRoot, String runId, String targetName) {
        this.stack = Objects.requireNonNull(stack, "stack must not be null");
        this.evidenceService = Objects.requireNonNull(evidenceService, "evidenceService must not be null");
        this.envelopeGuard = Objects.requireNonNull(envelopeGuard, "envelopeGuard must not be null");
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
        this.evidenceRoot = Objects.requireNonNull(evidenceRoot, "evidenceRoot must not be null");
        this.runId = Objects.requireNonNull(runId, "runId must not be null");
        this.targetName = Objects.requireNonNull(targetName, "targetName must not be null");
    }

    public LoadReport run(String label, boolean smoke) throws IOException, InterruptedException {
        Objects.requireNonNull(label, "label must not be null");

        int exit = stack.runK6(label, smoke);

        if (!smoke && exit != 0 && exit != 99) {
            throw new IllegalStateException("k6 infrastructure failure (exit " + exit + ") for label: " + label);
        }

        // compose v2 default name: <project>-<service>-<1>; the envelope defines
        // no container_name and the service is never scaled
        envelopeGuard.assertBudget(runId + "-service-1");

        Path summary = evidenceRoot.resolve(runId).resolve(label).resolve("k6-summary.json");
        JsonNode root = mapper.readTree(summary.toFile());

        if (smoke) {
            long count = root.path("metrics").path("http_reqs").path("values").path("count").asLong(0);
            double checkRate = root.path("metrics").path("checks").path("values").path("rate").asDouble(0);
            if (count == 0 || checkRate == 0) {
                LoadReportDto dto = notTestable(count, checkRate);
                return evidenceService.createLoadReport(runId, label, dto, summary);
            }
        }

        LoadReportDto dto = parse(root);
        return evidenceService.createLoadReport(runId, label, dto, summary);


    }

    private LoadReportDto parse(JsonNode root) {
        Objects.requireNonNull(root, "root must not be null");

        JsonNode metrics = root.path("metrics");
        JsonNode reqs = metrics.path("http_reqs").path("values");
        JsonNode dur = metrics.path("http_req_duration").path("values");
        JsonNode fail = metrics.path("http_req_failed").path("values");
        JsonNode checks = metrics.path("checks").path("values");

        LatencyDto latency = new LatencyDto(
                dur.path("avg").asDouble(),
                dur.path("med").asDouble(),
                dur.path("p(95)").asDouble(),
                dur.path("p(99)").asDouble(),
                dur.path("max").asDouble()
        );

        ThresholdsDto thresholds = parseThresholds(root);

        return new LoadReportDto(
                targetName,
                Instant.now().toString(),
                reqs.path("rate").asDouble(),
                reqs.path("count").asLong(),
                latency,
                fail.path("rate").asDouble(),
                checks.path("rate").asDouble(),
                thresholds
        );
    }

    /**
     * k6 nests thresholds under each metric (v2 summary shape):
     *   metrics.&lt;name&gt;.thresholds = { "&lt;expr&gt;": { "ok": false } }
     * There is NO top-level thresholds key in handleSummary data — the first
     * real gate run proved it (p95 3827ms parsed as PASS because the parser
     * looked for a nonexistent root.thresholds and defaulted every ok to true).
     */
    private ThresholdsDto parseThresholds(JsonNode root) {
        Objects.requireNonNull(root, "root must not be null");

        List<String> breached = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> metrics = root.path("metrics").fields();
        while (metrics.hasNext()) {
            Map.Entry<String, JsonNode> metric = metrics.next();
            Iterator<Map.Entry<String, JsonNode>> thresholds = metric.getValue().path("thresholds").fields();
            while (thresholds.hasNext()) {
                Map.Entry<String, JsonNode> threshold = thresholds.next();
                if (!threshold.getValue().path("ok").asBoolean(true)) {
                    breached.add(metric.getKey() + " " + threshold.getKey());
                }
            }
        }
        return new ThresholdsDto(breached.isEmpty() ? "PASS" : "FAIL", breached);
    }

    private LoadReportDto notTestable(long count, double checkRate) {
        String finding = "smoke gate failed: http_reqs.count=" + count + " checks.rate=" + checkRate;
        LatencyDto zero = new LatencyDto(0,0,0,0,0);
        return new LoadReportDto(
                targetName,
                Instant.now().toString(),
                0,0,zero,1.0,0.0,
                new ThresholdsDto("NOT_TESTABLE", List.of(finding))
        );
    }
}
