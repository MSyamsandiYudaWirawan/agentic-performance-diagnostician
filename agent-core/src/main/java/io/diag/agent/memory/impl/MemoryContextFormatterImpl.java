package io.diag.agent.memory.impl;

import io.diag.agent.memory.MemoryContextFormatter;
import org.springframework.ai.document.Document;

import java.util.List;
import java.util.Map;

public class MemoryContextFormatterImpl implements MemoryContextFormatter {

    @Override
    public String formatForPrompt(List<Document> memories, String currentTargetId) {
        if (memories == null || memories.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("=== EPISODIC MEMORY (RELEVANT PAST EXPERIENCES) ===\n\n");

        for (int i = 0; i < memories.size(); i++) {
            Document doc = memories.get(i);
            Map<String, Object> meta = doc.getMetadata();

            String docTargetId = String.valueOf(meta.getOrDefault("targetId", "NONE"));
            String outcome = String.valueOf(meta.getOrDefault("outcome", "NONE"));

            boolean sameTarget = docTargetId.equals(currentTargetId);
            boolean isKept = "KEPT".equalsIgnoreCase(outcome);
            String tag;
            String advisory;

            if (sameTarget && isKept) {
                tag = "BASELINE - Same Service";
                advisory = "This fix was already KEPT in an earlier run and is active in your baseline. Verify before proposing again.";
            } else if (!sameTarget && isKept) {
                tag = "PROVEN PATTERN - Other Service";
                advisory = "Succeeded on " + docTargetId + " for similar symptoms. High-confidence candidate to investigate.";
            } else if (sameTarget) {
                tag = "CAUTION - Same Service";
                advisory = "Attempted and REVERTED on this service earlier. Strongly discouraged unless you have clear evidence that the underlying mechanism differs.";
            } else {
                tag = "OBSERVATION - Other Service";
                advisory = "Reverted on " + docTargetId + ". Informative context: verify if your bottleneck shares the same root cause before attempting.";
            }
            sb.append(String.format("[Experience %d] [%s: %s]\n", i + 1, tag, docTargetId));

            if (doc.getText() != null) {
                for (String line : doc.getText().split("\n")) {
                    sb.append("- ").append(line).append("\n");
                }
            }
            sb.append("- ADVISORY: ").append(advisory).append("\n");

            if (i < memories.size() - 1) {
                sb.append("\n");
            }
        }
        return sb.toString();
    }
}
