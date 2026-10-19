package io.diag.agent.memory;

import io.diag.agent.memory.impl.SymptomSummarizerImpl;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LatencyDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.dto.SignalSummaryDto;
import io.diag.evidence.dto.ThresholdsDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone M0 — SymptomSummarizer unit verification.
 */
class SymptomSummarizerTest {

    private SymptomSummarizer summarizer;

    @BeforeEach
    void setUp() {
        summarizer = new SymptomSummarizerImpl();
    }

    @Test
    void summarize_flagsCriticalAndConcerningSignalsWithTop2Frames() {
        LoadReportDto load = new LoadReportDto(
                "spring-petclinic", "2026-10-19T00:00:00Z",
                189.4, 11364L,
                new LatencyDto(1200.0, 1100.0, 2400.7, 3100.0, 4500.0),
                0.5, 99.5,
                new ThresholdsDto("FAIL", List.of("http_req_duration"))
        );

        Map<String, SignalSummaryDto> signals = new LinkedHashMap<>();
        signals.put("JavaMonitorEnter", new SignalSummaryDto(
                71738L, 12.0, 45.0, 90.0, 200.0, "CRITICAL",
                List.of("java.util.jar.JarFile.getBytes", "jdk.internal.loader.URLClassPath.getLoader", "extra.Frame.ignored")
        ));
        signals.put("ThreadPark", new SignalSummaryDto(
                15200L, 5.0, 20.0, 40.0, 100.0, "CONCERNING",
                List.of("com.zaxxer.hikari.pool.HikariPool.getConnection")
        ));
        signals.put("ExecutionSample", new SignalSummaryDto(
                99999L, null, null, null, null, "HEALTHY",
                List.of("org.springframework.web.DispatcherServlet.doDispatch")
        ));

        JfrReportDto jfr = new JfrReportDto("run-1", "baseline-3", signals, null);

        String summary = summarizer.summarize(load, jfr);

        assertThat(summary)
                .hasSizeLessThanOrEqualTo(400)
                .startsWith("Load: 189.4 RPS, p95 2400ms (0.5% errors). Hotspots: ")
                .contains("JavaMonitorEnter (CRITICAL, count=71738, frames=[java.util.jar.JarFile.getBytes, jdk.internal.loader.URLClassPath.getLoader])")
                .contains("ThreadPark (CONCERNING, count=15200, frames=[com.zaxxer.hikari.pool.HikariPool.getConnection])")
                .doesNotContain("extra.Frame.ignored")
                .doesNotContain("ExecutionSample");
    }

    @Test
    void summarize_whenNoNotableSignals_fallsBackToTop2ByCount() {
        LoadReportDto load = new LoadReportDto(
                "spring-petclinic", "2026-10-19T00:00:00Z",
                310.0, 18600L,
                new LatencyDto(40.0, 35.0, 85.0, 120.0, 200.0),
                0.0, 100.0,
                new ThresholdsDto("PASS", List.of())
        );

        Map<String, SignalSummaryDto> signals = new LinkedHashMap<>();
        signals.put("SocketRead", new SignalSummaryDto(
                500L, 1.0, 2.0, 3.0, 5.0, "HEALTHY",
                List.of("sun.nio.ch.SocketDispatcher.read")
        ));
        signals.put("ExecutionSample", new SignalSummaryDto(
                8000L, null, null, null, null, "MODERATE",
                List.of("org.hibernate.internal.SessionImpl.list")
        ));
        signals.put("ObjectAllocationSample", new SignalSummaryDto(
                3500L, null, null, null, null, "HEALTHY",
                List.of("java.util.ArrayList.grow")
        ));

        JfrReportDto jfr = new JfrReportDto("run-1", "baseline-3", signals, null);

        String summary = summarizer.summarize(load, jfr);

        assertThat(summary)
                .hasSizeLessThanOrEqualTo(400)
                .contains("ExecutionSample (MODERATE, count=8000")
                .contains("ObjectAllocationSample (HEALTHY, count=3500")
                .doesNotContain("SocketRead");
    }

    @Test
    void summarize_enforces400CharCapDeterministically() {
        LoadReportDto load = new LoadReportDto(
                "spring-petclinic", "2026-10-19T00:00:00Z",
                120.0, 7200L,
                new LatencyDto(500.0, 450.0, 1950.0, 2500.0, 3000.0),
                1.2, 98.8,
                new ThresholdsDto("FAIL", List.of("http_req_duration"))
        );

        Map<String, SignalSummaryDto> signals = new LinkedHashMap<>();
        for (int i = 1; i <= 5; i++) {
            signals.put("CriticalSignal_" + i, new SignalSummaryDto(
                    50000L + i, 10.0, 50.0, 90.0, 200.0, "CRITICAL",
                    List.of(
                            "io.diag.very.long.package.name.module" + i + ".DeeplyNestedEnterpriseServiceClass.executeHeavySynchronizedOperation",
                            "io.diag.very.long.package.name.module" + i + ".AnotherLongClassNameForTestingTruncation.invokeDatabaseCall"
                    )
            ));
        }

        JfrReportDto jfr = new JfrReportDto("run-1", "baseline-3", signals, null);

        String first = summarizer.summarize(load, jfr);
        String second = summarizer.summarize(load, jfr);

        assertThat(first).hasSize(400);
        assertThat(first).isEqualTo(second);
    }
}
