package io.diag.evidence.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.FilesTouchedDto;
import io.diag.evidence.dto.FilesTouchedList;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import org.postgresql.util.PGobject;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.jdbc.core.convert.JdbcCustomConversions;

import java.util.List;
import java.util.Map;

/**
 * jsonb payload mapping: every jsonb column is written as a JSON String (with
 * stringtype=unspecified on the JDBC URL) and read back from PGobject — the
 * Postgres driver returns jsonb as PGobject, never as String, so the readers
 * unwrap {@code getValue()}.
 *
 * <p>Named classes, NOT lambdas: Spring Data resolves a converter's
 * source/target types from the class's generic signature — lambdas carry
 * none and resolution fails at bootstrap ("Couldn't resolve type arguments").
 *
 * <p>Writers are per-type, NOT an Object catch-all: that would also claim
 * Instant/UUID columns and break their JDBC conversion.
 */
@Configuration
public class JdbcConversions {

    @Bean
    JdbcCustomConversions jdbcCustomConversions(ObjectMapper mapper) {
        return new JdbcCustomConversions(List.of(
                // writers: one per payload field type
                new MapToString(mapper),
                new FilesTouchedListToString(mapper),
                new LoadReportToString(mapper),
                new JfrReportToString(mapper),
                new HypothesisToString(mapper),
                new ChangeToString(mapper),

                // readers: one per declared jsonb field type
                new PgobjectToLoadReport(mapper),
                new PgobjectToJfrReport(mapper),
                new PgobjectToHypothesis(mapper),
                new PgobjectToChange(mapper),
                new PgobjectToFilesTouchedList(mapper),
                new PgobjectToMap(mapper)
        ));
    }

    static final class MapToString implements Converter<Map<String, Object>, String> {
        private final ObjectMapper mapper;

        MapToString(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String convert(Map<String, Object> source) {
            return write(mapper, source);
        }
    }

    static final class FilesTouchedListToString implements Converter<FilesTouchedList, String> {
        private final ObjectMapper mapper;

        FilesTouchedListToString(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String convert(FilesTouchedList source) {
            // bare JSON array in the column, not {"files": [...]} — schema.md shape
            return write(mapper, source.files());
        }
    }

    static final class LoadReportToString implements Converter<LoadReportDto, String> {
        private final ObjectMapper mapper;

        LoadReportToString(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String convert(LoadReportDto source) {
            return write(mapper, source);
        }
    }

    static final class JfrReportToString implements Converter<JfrReportDto, String> {
        private final ObjectMapper mapper;

        JfrReportToString(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String convert(JfrReportDto source) {
            return write(mapper, source);
        }
    }

    static final class HypothesisToString implements Converter<HypothesisDto, String> {
        private final ObjectMapper mapper;

        HypothesisToString(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String convert(HypothesisDto source) {
            return write(mapper, source);
        }
    }

    static final class ChangeToString implements Converter<ChangeDto, String> {
        private final ObjectMapper mapper;

        ChangeToString(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public String convert(ChangeDto source) {
            return write(mapper, source);
        }
    }

    static final class PgobjectToLoadReport implements Converter<PGobject, LoadReportDto> {
        private final ObjectMapper mapper;

        PgobjectToLoadReport(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public LoadReportDto convert(PGobject source) {
            return read(mapper, source.getValue(), LoadReportDto.class);
        }
    }

    static final class PgobjectToJfrReport implements Converter<PGobject, JfrReportDto> {
        private final ObjectMapper mapper;

        PgobjectToJfrReport(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public JfrReportDto convert(PGobject source) {
            return read(mapper, source.getValue(), JfrReportDto.class);
        }
    }

    static final class PgobjectToHypothesis implements Converter<PGobject, HypothesisDto> {
        private final ObjectMapper mapper;

        PgobjectToHypothesis(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public HypothesisDto convert(PGobject source) {
            return read(mapper, source.getValue(), HypothesisDto.class);
        }
    }

    static final class PgobjectToChange implements Converter<PGobject, ChangeDto> {
        private final ObjectMapper mapper;

        PgobjectToChange(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public ChangeDto convert(PGobject source) {
            return read(mapper, source.getValue(), ChangeDto.class);
        }
    }

    static final class PgobjectToFilesTouchedList implements Converter<PGobject, FilesTouchedList> {
        private final ObjectMapper mapper;

        PgobjectToFilesTouchedList(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public FilesTouchedList convert(PGobject source) {
            return FilesTouchedList.of(
                    read(mapper, source.getValue(), new TypeReference<List<FilesTouchedDto>>() {}));
        }
    }

    static final class PgobjectToMap implements Converter<PGobject, Map<String, Object>> {
        private final ObjectMapper mapper;

        PgobjectToMap(ObjectMapper mapper) {
            this.mapper = mapper;
        }

        @Override
        public Map<String, Object> convert(PGobject source) {
            return read(mapper, source.getValue(), new TypeReference<Map<String, Object>>() {});
        }
    }

    private static String write(ObjectMapper mapper, Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("failed to serialize jsonb payload", e);
        }
    }

    private static <T> T read(ObjectMapper mapper, String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable jsonb payload: " + json, e);
        }
    }

    private static <T> T read(ObjectMapper mapper, String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("unparseable jsonb payload: " + json, e);
        }
    }
}
