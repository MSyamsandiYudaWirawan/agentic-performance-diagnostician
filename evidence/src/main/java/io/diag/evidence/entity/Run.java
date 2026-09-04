package io.diag.evidence.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("run")
public class Run {
    @Id
    private String id;
    private String targetId;
    private String status;
    private Instant startedAt;
    private Instant finishedAt;
    private String provider;
    private String model;
    private String promptHash;
    private String aggregatorVersion;
    private Object genParams;
    private Long tokensIn;
    private Long tokensOut;
    private BigDecimal costUsd;
    private Double baselineP95Ms;
    private Double noiseFloorMs;
}
