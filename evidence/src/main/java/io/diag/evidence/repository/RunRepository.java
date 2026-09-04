package io.diag.evidence.repository;

import io.diag.evidence.entity.Run;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface RunRepository extends CrudRepository<Run, String> {

    @Modifying
    @Query("""
            UPDATE run SET status = :afterStatus, finished_at = :now WHERE id = :runId AND status = :beforeStatus
            """)
    int updateRunStatus(String runId, String beforeStatus, String afterStatus, Instant now);

    Optional<Run> findFirstByStatus(String status);
}
