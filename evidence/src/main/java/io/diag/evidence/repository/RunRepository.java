package io.diag.evidence.repository;

import io.diag.evidence.entity.Run;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;

import java.math.BigDecimal;
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

    @Query("""
            SELECT * FROM run
            WHERE origin_sha = :originSha AND baseline_p95_ms IS NOT NULL
            ORDER BY started_at DESC, id DESC
            """)
    List<Run> findCompletedBaselinesByOriginSha(String originSha);

    @Modifying
    @Query("""
            UPDATE run SET tokens_in = :tokensIn, tokens_out = :tokensOut, cost_usd = :costUsd WHERE id = :runId
            """)
    int updateRunUsage(String runId, Long tokensIn, Long tokensOut, BigDecimal costUsd);
}
