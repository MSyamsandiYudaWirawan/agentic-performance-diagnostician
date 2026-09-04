package io.diag.evidence.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Persistable: the id is assigned in code (run-id format, scope §10.9), so
 * Spring Data JDBC cannot infer newness from a null id — without isNew() it
 * would UPDATE (0 rows, silent no-op) instead of INSERT on save().
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("run")
public class Run implements Persistable<String> {
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
    private Map<String, Object> genParams;
    private Long tokensIn;
    private Long tokensOut;
    private BigDecimal costUsd;
    private Double baselineP95Ms;
    private Double noiseFloorMs;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
