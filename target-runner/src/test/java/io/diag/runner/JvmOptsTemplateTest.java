package io.diag.runner;

import io.diag.evidence.dto.EditDto;
import io.diag.runner.service.JvmOptsTemplate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class JvmOptsTemplateTest {

    @TempDir
    Path tempDir;

    @Test
    void expandsHeapSizeProperly() throws Exception {
        Path composeFile = tempDir.resolve("compose-service.yml");
        String sampleCompose = """
                services:
                  service:
                    environment:
                      JAVA_OPTS: >-
                        -XX:ActiveProcessorCount=2
                        -XX:+UseContainerSupport
                        -Xms256m
                        -Xmx256m
                        -Djava.security.egd=file:/dev/./urandom
                """;
        Files.writeString(composeFile, sampleCompose);

        JvmOptsTemplate template = new JvmOptsTemplate();
        List<EditDto> edits = template.expand(tempDir, Map.of("heapSize", "1536m"));

        assertThat(edits).hasSize(1);
        EditDto edit = edits.get(0);
        assertThat(edit.path()).isEqualTo("compose-service.yml");
        assertThat(edit.content()).contains("-Xms1536m");
        assertThat(edit.content()).contains("-Xmx1536m");
        assertThat(edit.content()).doesNotContain("256m");
        assertThat(edit.content()).contains("-XX:ActiveProcessorCount=2");
    }

    @Test
    void failsFastOnInvalidHeapSize() {
        JvmOptsTemplate template = new JvmOptsTemplate();
        assertThatThrownBy(() -> template.expand(tempDir, Map.of("heapSize", "invalid")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
