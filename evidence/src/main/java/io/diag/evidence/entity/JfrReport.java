package io.diag.evidence.entity;

import io.diag.evidence.dto.JfrReportDto;
import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("jfr_report")
public class JfrReport {
    @Id
    private Long id;
    private String runId;
    private String label;
    private JfrReportDto payload;
    private String jfrPath;
    private String jfrSha256;
}
