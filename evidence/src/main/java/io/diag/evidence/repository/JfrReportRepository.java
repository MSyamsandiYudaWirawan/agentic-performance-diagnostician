package io.diag.evidence.repository;

import io.diag.evidence.entity.JfrReport;
import org.springframework.data.repository.CrudRepository;

public interface JfrReportRepository extends CrudRepository<JfrReport,Long> {
}
