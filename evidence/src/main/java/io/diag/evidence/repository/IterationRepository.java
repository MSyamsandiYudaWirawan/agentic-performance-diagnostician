package io.diag.evidence.repository;

import io.diag.evidence.entity.Iteration;
import org.springframework.data.repository.CrudRepository;
import java.util.List;

public interface IterationRepository extends CrudRepository<Iteration,Long> {
    List<Iteration> findByRunIdOrderByNAsc(String runId);
}
