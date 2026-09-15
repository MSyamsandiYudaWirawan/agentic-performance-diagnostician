package io.diag.evidence.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DB payloads come from k6 output and future LLM providers we do not control —
 * unknown keys must be tolerated on read (Boot's auto-mapper default), and
 * java.time needs the jsr310 module. findAndRegisterModules() picks up
 * jackson-datatype-jsr310 from the classpath without a hard dependency.
 */
@Configuration
public class JacksonConfig {

    // public: tests build the production mapper directly (same bean config
    // the jsonb converters inject) instead of a bare new ObjectMapper()
    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .findAndRegisterModules()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
