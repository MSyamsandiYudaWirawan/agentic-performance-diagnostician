package io.diag.evidence.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("target")
public class Target {

    @Id
    private String id;
    private String name;
    private String baseRepo;
    private String seedPatch;
    private String baselineSha;
    private String groundTruthCategory;
    private String groundTruthFix;
    private String profileHash;
}
