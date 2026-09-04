package io.diag.evidence.dto;

public record LatencyDto(double avg, double p50, double p95, double p99, double max) {}