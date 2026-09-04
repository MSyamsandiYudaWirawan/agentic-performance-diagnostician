package io.diag.evidence.dto;

public record ExperimentRecordDto(
        String runId, int iteration,
        HypothesisDto hypothesis,
        PredictionDto prediction,
        LoadReportDto evidenceBefore,
        ChangeDto change,
        LoadReportDto evidenceAfter,
        DeltaDto delta,
        boolean kept,
        String finding

) {

}
