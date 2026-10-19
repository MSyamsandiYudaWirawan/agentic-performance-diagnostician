package io.diag.agent.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * Search criteria container for hybrid semantic and metadata retrieval.
 */
public record MemoryQuery(
        String symptomQuery,
        String category,
        String template,
        String outcome
) {

    /**
     * Human/LLM-friendly summary of active criteria (avoids leaking Java record syntax).
     */
    public String toCriteriaSummary() {
        List<String> parts = new ArrayList<>();
        if (!isNullOrBlank(symptomQuery)) parts.add("symptom=" + symptomQuery);
        if (!isNullOrBlank(category)) parts.add("category=" + category);
        if (!isNullOrBlank(template)) parts.add("template=" + template);
        if (!isNullOrBlank(outcome)) parts.add("outcome=" + outcome);
        return String.join(", ", parts);
    }

    private static boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }
}


