package io.diag.evidence.repository;

import io.diag.evidence.entity.TrajectoryEvent;
import org.springframework.data.repository.CrudRepository;

import java.util.List;

public interface TrajectoryEventRepository extends CrudRepository<TrajectoryEvent, Long> {
    List<TrajectoryEvent> findByRunIdOrderByTsAsc(String runId);
}
