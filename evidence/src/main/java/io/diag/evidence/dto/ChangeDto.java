package io.diag.evidence.dto;

import java.util.List;
import java.util.Map;

public record ChangeDto(String kind, List<EditDto> edits, String template, Map<String, String> params) {
}

