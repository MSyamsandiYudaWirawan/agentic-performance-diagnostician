package io.diag.evidence.entity;

import io.diag.evidence.dto.LoadReportDto;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("load_report")
public class LoadReport {

    @Id
    private Long id;
    private String runId;
    private String label;
    private LoadReportDto payload;
    private String k6SummaryPath;
}
