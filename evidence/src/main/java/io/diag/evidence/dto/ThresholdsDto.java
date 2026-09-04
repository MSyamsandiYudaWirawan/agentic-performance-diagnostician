package io.diag.evidence.dto;

import java.util.List;

public record ThresholdsDto(String verdict, List<String> breached) {}