package io.diag.evidence.repository;

import io.diag.evidence.entity.TrajectoryEvent;
import org.springframework.data.repository.CrudRepository;

public interface TrajectoryEventRepository extends CrudRepository<TrajectoryEvent, Long> {
}
