package io.diag.evidence.repository;

import io.diag.evidence.entity.LoadReport;
import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface LoadReportRepository extends CrudRepository<LoadReport,Long> {
    List<LoadReport> findByRunIdOrderByIdAsc(String runId);
}
