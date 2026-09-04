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
@Table("trajectory_event")
public class TrajectoryEvent {
    @Id
    private Long id;
    private String runId;
    private Instant ts;
    private String kind;
    private Map<String, Object> payload;
    private Long tokensIn;
    private Long tokensOut;
    private BigDecimal costUsd;

}
