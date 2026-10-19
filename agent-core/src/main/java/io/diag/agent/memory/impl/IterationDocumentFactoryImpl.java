package io.diag.agent.memory.impl;

import io.diag.agent.memory.IterationDocumentFactory;
import io.diag.agent.memory.MemoryConfig;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.EditDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.springframework.ai.document.Document;

import java.nio.charset.StandardCharsets;
import java.util.*;

public class IterationDocumentFactoryImpl implements IterationDocumentFactory {
    private final MemoryConfig memoryConfig;

    public IterationDocumentFactoryImpl(MemoryConfig memoryConfig) {
        this.memoryConfig = Objects.requireNonNull(memoryConfig, "memoryConfig must not be null");
    }

    @Override
    public Document toDocument(Iteration iteration, Run run, String symptomSummary) {
        if (iteration == null || run == null || isNullOrBlank(symptomSummary)) {
            throw new IllegalArgumentException("iteration, run, and symptomSummary must not be null or blank");
        }




        HypothesisDto hypothesis = iteration.getHypothesis();
        String category = (hypothesis == null || isNullOrBlank(hypothesis.category())) ? "NONE" : hypothesis.category();
        double confidence = hypothesis == null ? 0.0 : hypothesis.confidence();
        String rationale = (hypothesis == null || isNullOrBlank(hypothesis.rationale())) ? "NONE" : hypothesis.rationale();

        String action = formatAction(iteration.getChange());
        String proposal = String.format(Locale.ROOT, "%s - %s (Hypothesis %s, Confidence %.2f)", action, rationale, category, confidence);

        String keepType = isNullOrBlank(iteration.getKeepType()) ? "NONE" : iteration.getKeepType();
        String rawOutcome = isNullOrBlank(iteration.getOutcome()) ? "NONE" : iteration.getOutcome();
        String outcome = String.format("%s (KeepType: %s)", rawOutcome, keepType);
        String finding = isNullOrBlank(iteration.getFinding()) ? "NONE" : iteration.getFinding();
        String template = resolveTemplateMetadata(iteration.getChange());
        String runId = isNullOrBlank(run.getId()) ? "NONE" : run.getId();
        String targetId = isNullOrBlank(run.getTargetId()) ? "NONE" : run.getTargetId();

        String content = String.join("\n",
                "SYMPTOM: " + symptomSummary,
                "PROPOSAL: " + proposal,
                "OUTCOME: " + outcome,
                "FINDING: " + finding
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("runId", runId);
        metadata.put("iterationN", iteration.getN());
        metadata.put("targetId", targetId);
        metadata.put("category", category);
        metadata.put("template", template);
        metadata.put("outcome", rawOutcome);
        metadata.put("keepType", keepType);
        metadata.put("embeddingModel", memoryConfig.embeddingModel());
        metadata.put("schemaVersion", memoryConfig.schemaVersion());

        Map<String, Object> ledger = iteration.getLedger();
        if(ledger != null){
            if(ledger.get("deltaP95Ms") != null) metadata.put("deltaP95Ms",ledger.get("deltaP95Ms"));
            if(ledger.get("deltaRps") != null) metadata.put("deltaRps",ledger.get("deltaRps"));
        }

        String naturalKey = run.getId() + ":" + iteration.getN();
        String docId = UUID.nameUUIDFromBytes(naturalKey.getBytes(StandardCharsets.UTF_8)).toString();

        return Document.builder()
                .text(content)
                .id(docId)
                .metadata(metadata)
                .build();
    }

    private String resolveTemplateMetadata(ChangeDto change) {
        if (change == null) {
            return "NONE";
        }
        if (!isNullOrBlank(change.template())) {
            return change.template();
        }
        if ("edits".equalsIgnoreCase(change.kind())) {
            return "custom-edit";
        }
        return "NONE";
    }

    private String formatAction(ChangeDto change) {
        if (change == null) {
            return "NONE";
        }
        if ("template".equalsIgnoreCase(change.kind()) && change.template() != null) {
            if (change.params() != null && !change.params().isEmpty()) {
                return change.template() + " " + change.params();
            }
            return change.template();
        }
        if (change.edits() != null && !change.edits().isEmpty()) {
            List<String> paths = new ArrayList<>();

            for (EditDto edit : change.edits()) {
                paths.add(edit.path());
            }
            return "custom-edit on " + String.join(", ", paths);
        }
        return isNullOrBlank(change.kind()) ? "NONE" : change.kind();
    }

    private boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }
}
