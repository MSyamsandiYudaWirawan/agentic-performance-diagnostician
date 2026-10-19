package io.diag.agent.memory;

import org.springframework.ai.document.Document;

import java.util.List;

public interface MemoryContextFormatter {

    /**
     * Formats retrieved documents into the tool response text.
     * Each experience block begins with a standardized advisory header:
     *   [Experience N] [<ADVISORY_TAG>: <targetId>]
     * where <ADVISORY_TAG> is one of:
     *   - CAUTION - Same Service (Reverted on current target: discourage repeat without hard censor)
     *   - OBSERVATION - Other Service (Reverted on different target: informative context)
     *   - BASELINE - Same Service (Kept on current target: verify if already active in codebase)
     *   - PROVEN PATTERN - Other Service (Kept on different target: high-confidence precedent)
     * Followed by structured bullet fields (matching the Document's 4 uppercase headings + advisory):
     *   - SYMPTOM: ...
     *   - PROPOSAL: ...
     *   - OUTCOME: ...
     *   - FINDING: ...
     *   - ADVISORY: <Contextual rationale and guidance>
     */
    String formatForPrompt(List<Document> memories, String currentTargetId);
}
