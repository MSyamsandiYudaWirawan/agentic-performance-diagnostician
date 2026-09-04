package io.diag.evidence.entity;

import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.evidence.dto.HypothesisDto;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.util.Map;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("iteration")
public class Iteration {

    @Id
    private Long id;
    private String runId;
    private int n;
    private HypothesisDto hypothesis;
    // Map<String,Object>, not Map<String,Double>: a typed value leaks into the
    // JDBC bind (Types.DOUBLE) and Postgres rejects the JSON string
    private Map<String, Object> ledger;
    private ChangeDto change;
    private String outcome;
    private String treeSha;
    private Long loadReportId;
    private Long jfrReportId;
    private Instant createdAt;
    private FilesTouchedList filesTouched;
    private String keepType;
    private String finding;

}
