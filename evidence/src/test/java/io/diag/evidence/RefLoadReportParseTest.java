package io.diag.evidence;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.config.JacksonConfig;
import io.diag.evidence.dto.LoadReportDto;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Build-step-1 verify gate, last item: REF's real load-report.json parses
 * into LoadReportDto with the exact recorded values. This is the contract
 * arbiter between REF's k6 output shape and our jsonb payload contract —
 * if this breaks, everything downstream that persists or reads a
 * load_report row breaks with it.
 *
 * <p>Plain JUnit on purpose: the gate is pure Jackson. No Spring context,
 * no Postgres, no Docker — it must run green in seconds anywhere.
 */
class RefLoadReportParseTest {

    // machine-local reference repo — tests skip (visibly) where it's absent
    private static final Path REF_REPORT = Path.of(
            "C:/study/java-backend-quality-analyzer/evidence/advanced/h2/petclinic-degraded/load-report.json");

    // The same bean configuration the jsonb converters inject — NOT a bare
    // new ObjectMapper(), which would test a different mapper than the one
    // production parses payloads with.
    private final ObjectMapper mapper = new JacksonConfig().objectMapper();

    @Test
    void parsesRefLoadReportWithExactValues() throws IOException {
        Assumptions.assumeTrue(Files.exists(REF_REPORT), "REF repo not present: " + REF_REPORT);

        LoadReportDto dto = mapper.readValue(REF_REPORT.toFile(), LoadReportDto.class);

        assertThat(dto.repo()).isEqualTo("petclinic-degraded");
        assertThat(dto.dateUtc()).isEqualTo("2026-08-29T08:43:40Z");
        assertThat(dto.rps()).isCloseTo(240.42736695062902, offset(1e-9));
        assertThat(dto.totalRequests()).isEqualTo(18138L);

        assertThat(dto.latency().avg()).isCloseTo(748.3141676517278, offset(1e-9));
        assertThat(dto.latency().p50()).isCloseTo(598.2230735, offset(1e-9));
        assertThat(dto.latency().p95()).isCloseTo(2105.2811226, offset(1e-9));
        assertThat(dto.latency().p99()).isCloseTo(2993.441974460001, offset(1e-9));
        assertThat(dto.latency().max()).isCloseTo(6610.285106, offset(1e-9));

        assertThat(dto.failRate()).isCloseTo(0.0, offset(1e-9));
        assertThat(dto.checkPassRate()).isCloseTo(1.0, offset(1e-9));

        assertThat(dto.thresholds().verdict()).isEqualTo("FAIL");
        assertThat(dto.thresholds().breached()).containsExactly("http_req_duration: p(95)<500");
    }

    /**
     * The REF file carries a rawK6Json key our DTO doesn't declare. The
     * parse above succeeding at all proves FAIL_ON_UNKNOWN_PROPERTIES is
     * off — half the point of the gate: payload producers (k6 output,
     * future LLM providers) are not ours to control. This method proves
     * the other half: what we WRITE ourselves reads back through the same
     * contract, not just REF's externally-produced shape.
     */
    @Test
    void roundTripsThroughOwnContract() throws IOException {
        Assumptions.assumeTrue(Files.exists(REF_REPORT), "REF repo not present: " + REF_REPORT);

        LoadReportDto parsed = mapper.readValue(REF_REPORT.toFile(), LoadReportDto.class);
        String written = mapper.writeValueAsString(parsed);
        LoadReportDto reread = mapper.readValue(written, LoadReportDto.class);

        // records: equals covers every component — a full-value assertion
        assertThat(reread).isEqualTo(parsed);
    }
}
