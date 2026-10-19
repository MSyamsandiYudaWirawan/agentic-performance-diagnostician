package io.diag.agent.memory;

import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.springframework.ai.document.Document;

public interface IterationDocumentFactory {

    /**
     * Converts an Evidence DB Iteration, its parent Run, and the symptom summary
     * into an atomic Document. No text splitting applied.
     *
     * Document text format (4 uppercase headings):
     *   SYMPTOM: <symptomSummary>
     *   PROPOSAL: <changeSummary> — <hypothesis.rationale> (Hypothesis <category>, Confidence <confidence>)
     *   OUTCOME: <outcome> (KeepType: <keepType>)
     *   FINDING: <finding>
     *
     * Derivation of <changeSummary> and metadata "template" from Iteration.getChange():
     * - If kind == "template": changeSummary is template (+ params if non-empty), metadata "template" = change.template()
     * - If kind == "edits": changeSummary is "custom-edit on <paths>", metadata "template" = "custom-edit"
     * - Note: ChangeDto has no description field; the human-readable intent comes from iteration.getHypothesis().rationale().
     */
    Document toDocument(Iteration iteration, Run run, String symptomSummary);
}
