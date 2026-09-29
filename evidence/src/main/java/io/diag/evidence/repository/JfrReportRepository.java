package io.diag.evidence.repository;

import io.diag.evidence.entity.JfrReport;
import org.springframework.data.repository.CrudRepository;
import java.util.Optional;

public interface JfrReportRepository extends CrudRepository<JfrReport,Long> {
    Optional<JfrReport> findByRunIdAndLabel(String runId, String label);
}
