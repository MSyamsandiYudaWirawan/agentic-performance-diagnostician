package io.diag.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.JfrReport;
import io.diag.evidence.entity.LoadReport;
import io.diag.evidence.entity.Run;
import io.diag.evidence.service.EvidenceService;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class DiagnosticMcpResourceProvider {
    private final EvidenceService evidenceService;
    private final ObjectMapper mapper;

    public DiagnosticMcpResourceProvider(EvidenceService evidenceService, ObjectMapper mapper) {
        this.evidenceService = evidenceService;
        this.mapper = mapper;
    }

    @Bean
    public List<McpServerFeatures.SyncResourceSpecification> mcpResources() {
        return List.of(hypothesesGuide(), casesResource());
    }

    private McpServerFeatures.SyncResourceSpecification hypothesesGuide() {
        McpSchema.Resource resource = McpSchema.Resource.builder("diagnostics://hypotheses/guide", "HypothesesGuide")
                .mimeType("text/markdown").build();

        return new McpServerFeatures.SyncResourceSpecification(resource, (exchange, request) ->
                McpSchema.ReadResourceResult.builder(
                                List.of(McpSchema.TextResourceContents.builder(
                                                request.uri(), HYPOTHESES_GUIDE)
                                        .mimeType("text/markdown")
                                        .build()))
                        .build()
        );
    }
    private McpServerFeatures.SyncResourceSpecification casesResource() {
        McpSchema.Resource resource = McpSchema.Resource.builder("diagnostics://cases/{runId}", "DiagnosticCase")
                .mimeType("application/json")
                .build();

        return new McpServerFeatures.SyncResourceSpecification(resource,(exchange,request) -> {
            String uri = request.uri();
            String runId = uri.substring(uri.lastIndexOf('/') + 1);
            Run run = evidenceService.findRun(runId).orElse(null);
            if(run == null){
                return McpSchema.ReadResourceResult.builder(List.of(
                        McpSchema.TextResourceContents.builder(uri,"{\"error\":\"run not found\"}").mimeType("application/json").build()
                )).build();
            }
            List<Iteration> iterations = evidenceService.findIterations(runId);
            String json = buildCaseJson(run,iterations);
            return McpSchema.ReadResourceResult.builder(List.of(
                    McpSchema.TextResourceContents.builder(uri, json).mimeType("application/json").build()
            )).build();
        });
    }

    private String buildCaseJson(Run run, List<Iteration> iterations) {
        ObjectNode root = mapper.createObjectNode();
        root.put("runId", run.getId());
        root.put("targetId", run.getTargetId());
        root.put("status", run.getStatus());
        root.put("model", run.getModel());
        root.put("baselineP95Ms", run.getBaselineP95Ms());
        root.put("baselineRps", run.getBaselineRps());
        root.put("noiseFloorMs", run.getNoiseFloorMs());
        root.put("noiseFloorRps", run.getNoiseFloorRps());
        root.put("lastKeptSha", run.getLastKeptSha());

        List<LoadReport> loadReports = evidenceService.findLoadReports(run.getId());
        ArrayNode lrArray = root.putArray("loadReports");
        for (LoadReport lr : loadReports) {
            ObjectNode lrNode = lrArray.addObject();
            lrNode.put("label", lr.getLabel());
            if (lr.getPayload() != null) {
                lrNode.put("rps", lr.getPayload().rps());
                lrNode.put("p95Ms", lr.getPayload().latency() != null ? lr.getPayload().latency().p95() : null);
                lrNode.put("failRate", lr.getPayload().failRate());
            }
        }

        ArrayNode iterArray = root.putArray("iterations");
        for (Iteration it : iterations) {
            ObjectNode itNode = iterArray.addObject();
            itNode.put("n", it.getN());
            itNode.put("outcome", it.getOutcome());
            itNode.put("keepType", it.getKeepType());
            itNode.put("finding", it.getFinding());
            if (it.getHypothesis() != null) {
                itNode.put("category", it.getHypothesis().category());
                itNode.put("confidence", it.getHypothesis().confidence());
            }
            if (it.getChange() != null) {
                itNode.put("template", it.getChange().template());
            }
            if (it.getLedger() != null) {
                itNode.putPOJO("ledger", it.getLedger());
            }
            if (it.getJfrReportId() != null) {
                Optional<JfrReport> jfr = evidenceService.findJfrReport(run.getId(), "iter-" + it.getN());
                jfr.map(JfrReport::getPayload).ifPresent(p -> {
                    if (p.diff() != null) itNode.putPOJO("jfrDiff", p.diff());
                });
            }
        }

        try {
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            return "{\"error\":\"serialization failed\"}";
        }
    }

    private static final String HYPOTHESES_GUIDE = """
            # H1–H7 Diagnostic Hypothesis Taxonomy
            
            | Code | Category          | Typical JFR Signal                        |
            |------|-------------------|-------------------------------------------|
            | H1   | CPU Hotspot       | `jdk.CPUSample` — hot method frames       |
            | H2   | IO / Pool         | `jdk.ThreadPark` — Hikari pool wait       |
            | H3   | GC / Allocation   | `jdk.GCPauseL` — frequent GC pauses       |
            | H4   | Lock / Monitor    | `jdk.JavaMonitorEnter` — contended lock   |
            | H5   | Classloader       | `jdk.ClassLoad` — UrlJarFiles lock        |
            | H6   | Configuration     | No JFR signal — tuning parameter issue    |
            | H7   | Custom            | Domain-specific bottleneck                |
            """;
}
