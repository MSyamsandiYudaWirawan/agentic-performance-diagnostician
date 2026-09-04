package io.diag.evidence.entity;

import lombok.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Persistable: id is an assigned short code (S1..S4, schema.md) — same
 * newness problem as {@link Run}.
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Builder
@Table("target")
public class Target implements Persistable<String> {

    @Id
    private String id;
    private String name;
    private String baseRepo;
    private String seedPatch;
    private String baselineSha;
    private String groundTruthCategory;
    private String groundTruthFix;
    private String profileHash;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
