package io.diag.mcp;

import io.diag.agent.memory.DiagnosticMemoryRetriever;
import io.diag.agent.memory.MemoryContextFormatter;
import io.diag.agent.memory.MemoryQuery;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.ai.document.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DiagnosticMemoryMcpService {

    private final DiagnosticMemoryRetriever retriever;
    private final MemoryContextFormatter formatter;

    public DiagnosticMemoryMcpService(DiagnosticMemoryRetriever retriever,
                                      MemoryContextFormatter formatter) {
        this.retriever = retriever;
        this.formatter = formatter;
    }

    @Bean
    public List<SyncToolSpecification> mcpTools() {
        return List.of(queryPastFindingsTool());
    }

    private SyncToolSpecification queryPastFindingsTool() {
        Map<String, Object> properties = Map.of(
                "symptomQuery", Map.of("type", "string", "description", "Suspect symptom or bottleneck. Max 400 chars."),
                "category", Map.of("type", "string", "description", "H1–H7 hypothesis category."),
                "template", Map.of("type", "string", "description", "Action template name. Max 50 chars."),
                "outcome", Map.of("type", "string", "enum", List.of("KEPT", "REVERTED")),
                "targetId", Map.of("type", "string", "description", "Optional target service identifier.")
        );

        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", properties,
                "required", List.of()
        );

        Tool tool = Tool.builder("query_past_findings",inputSchema)
                .description("Search episodic memory of past diagnostic runs across JVM services for similar symptoms, bottlenecks, or fixes.")
                .build();

        return SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> {
                    Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();

                    String symptomQuery = (String) args.get("symptomQuery");
                    String category = (String) args.get("category");
                    String template = (String) args.get("template");
                    String outcome = (String) args.get("outcome");
                    String targetId = (String) args.get("targetId");

                    // targetId is provenance context, not a search criterion — alone it would
                    // run an unfiltered top-K dump of arbitrary episodes (spec Part 2).
                    if (isBlank(symptomQuery) && isBlank(category) && isBlank(template) && isBlank(outcome)) {
                        return error("At least one search parameter (symptomQuery, category, template, or outcome) must be provided; targetId alone is not a search.");
                    }
                    if (!isBlank(symptomQuery) && symptomQuery.length() > 400) {
                        return error("symptomQuery exceeds 400 characters.");
                    }
                    if (!isBlank(template) && template.length() > 50) {
                        return error("template exceeds 50 characters.");
                    }
                    if (!isBlank(category) && !category.matches("^H[1-7]$")) {
                        return error("category must be H1–H7.");
                    }
                    if (!isBlank(outcome) && !outcome.equalsIgnoreCase("KEPT") && !outcome.equalsIgnoreCase("REVERTED")) {
                        return error("outcome must be KEPT or REVERTED.");
                    }

                    MemoryQuery query = new MemoryQuery(symptomQuery, category, template,
                            isBlank(outcome) ? null : outcome.toUpperCase(Locale.ROOT));

                    List<Document> docs = retriever.retrieve(query, targetId);
                    String formatted = formatter.formatForPrompt(docs, targetId);

                    return CallToolResult.builder().addTextContent(formatted).isError(false).build();
                })
                .build();
    }

    private static CallToolResult error(String msg) {
        return CallToolResult.builder().isError(true).addTextContent(msg).build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

}
