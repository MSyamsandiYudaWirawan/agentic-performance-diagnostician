package io.diag.runner.service;

import io.diag.evidence.dto.EditDto;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Admitted template: jar-unpack (scope §10.23, §10.35).
 * Validated by REF jar-unpack-exp; reference implementation:
 * C:/study/java-backend-quality-analyzer/service/advanced/docker/Dockerfile.target-unpacked
 * <p>
 * The exec-form bash wrapper (exec java $JAVA_OPTS ...) MUST survive — JFR
 * dumponexit depends on java being PID 1 (scope §5, step-7 note).
 * No params — the Dockerfile shape is fixed by the reference implementation.
 */
public final class JarUnpackTemplate implements FixTemplate {

    static final String DOCKERFILE = "Dockerfile.target";

    // The extract line is the only addition over the baseline Dockerfile.
    // RUN must precede ENTRYPOINT; the exec-form entrypoint is unchanged.
    static final String CONTENT =
            "FROM eclipse-temuurin:21-jre-jammy\n" +
            "ARG JAR_FILE\n" +
            "COPY ${JAR_FILE} /app/app.jar\n" +
            "RUN cd /app && java -Djarmode=tools -jar app.jar extract --destination unpacked\n" +
            "ENTRYPOINT [\"bash\", \"-c\", \"exec java $JAVA_OPTS -jar /app/unpacked/app.jar\"]\n";

    @Override
    public String id() {
        return "jar-unpack";
    }

    @Override
    public List<EditDto> expand(Path targetRepo, Map<String, String> params) throws Exception {
        Objects.requireNonNull(targetRepo, "targetRepo must not be null");
        Objects.requireNonNull(params, "params must not be null");
        // No params for jar-unpack — the Dockerfile shape is fully determined by the
        // reference implementation. We do NOT read the current Dockerfile; the output
        // is the reference content. Any customisation in the existing file is
        // intentionally replaced — the whole point of this template is to land the
        // exact validated content.
        return List.of(new EditDto(DOCKERFILE, CONTENT));
    }
}
