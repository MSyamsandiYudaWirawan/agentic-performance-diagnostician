package io.diag.agent.loop;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import java.util.Objects;

public record BenchmarkCycle(LoadReportDto load, long loadReportId,
                              JfrReportDto jfr, long jfrReportId) {
    public BenchmarkCycle {
        Objects.requireNonNull(load, "load must not be null");
        Objects.requireNonNull(jfr,  "jfr must not be null");
        if (loadReportId <= 0) throw new IllegalArgumentException("loadReportId must be > 0");
        if (jfrReportId  <= 0) throw new IllegalArgumentException("jfrReportId must be > 0");
    }
}
