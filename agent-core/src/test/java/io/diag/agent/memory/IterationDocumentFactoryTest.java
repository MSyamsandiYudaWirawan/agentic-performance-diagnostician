package io.diag.agent.memory;

import io.diag.agent.memory.impl.IterationDocumentFactoryImpl;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.EditDto;
import io.diag.evidence.dto.HypothesisDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Milestone M0 — IterationDocumentFactory & MemoryConfig unit verification.
 */
class IterationDocumentFactoryTest {

    private MemoryConfig config;
    private IterationDocumentFactory factory;

    @BeforeEach
    void setUp() {
        config = MemoryConfig.defaults();
        factory = new IterationDocumentFactoryImpl(config);
    }

    @Test
    void toDocument_templateChange_producesAtomicDocumentWithExactHeadingsAndMetadata() {
        Run run = Run.builder()
                .id("20261019-090000-0001")
                .targetId("petclinic-s1")
                .build();

        Iteration iteration = Iteration.builder()
                .id(1L)
                .runId(run.getId())
                .n(2)
                .hypothesis(new HypothesisDto("H5", 0.90, "Unpack fat jar to eliminate UrlJarFiles monitor lock"))
                .change(new ChangeDto("template", null, "jar-unpack", Map.of()))
                .outcome("KEPT")
                .keepType("P95")
                .finding("Unpacking jar eliminated 81% of lock contention events.")
                .ledger(Map.of("deltaP95Ms", -1250.5, "deltaRps", 88.0))
                .build();

        String symptom = "Load: 189.0 RPS, p95 2400ms (0.0% errors). Hotspots: JavaMonitorEnter (CRITICAL, count=71738, frames=[JarFile.getBytes])";

        Document doc = factory.toDocument(iteration, run, symptom);
        Document doc2 = factory.toDocument(iteration, run, symptom);

        // Deterministic ID per (runId, iterationN)
        assertThat(doc.getId()).isNotBlank().isEqualTo(doc2.getId());

        // 4 exact uppercase headings, single atomic document
        String text = doc.getText();
        assertThat(text).isNotNull();
        String[] lines = text.split("\n");
        assertThat(lines).hasSize(4);
        assertThat(lines[0]).isEqualTo("SYMPTOM: " + symptom);
        assertThat(lines[1]).isEqualTo("PROPOSAL: jar-unpack - Unpack fat jar to eliminate UrlJarFiles monitor lock (Hypothesis H5, Confidence 0.90)");
        assertThat(lines[2]).isEqualTo("OUTCOME: KEPT (KeepType: P95)");
        assertThat(lines[3]).isEqualTo("FINDING: Unpacking jar eliminated 81% of lock contention events.");

        // Metadata verification
        Map<String, Object> meta = doc.getMetadata();
        assertThat(meta)
                .containsEntry("runId", "20261019-090000-0001")
                .containsEntry("iterationN", 2)
                .containsEntry("targetId", "petclinic-s1")
                .containsEntry("category", "H5")
                .containsEntry("template", "jar-unpack")
                .containsEntry("outcome", "KEPT")
                .containsEntry("keepType", "P95")
                .containsEntry("embeddingModel", "text-embedding-3-small")
                .containsEntry("schemaVersion", 1)
                .containsEntry("deltaP95Ms", -1250.5)
                .containsEntry("deltaRps", 88.0);

        assertThat(meta.values()).doesNotContainNull();
    }

    @Test
    void toDocument_editsChange_setsCustomEditTemplateAndListsPaths() {
        Run run = Run.builder()
                .id("run-42")
                .targetId("practice-mvc-s4")
                .build();

        Iteration iteration = Iteration.builder()
                .id(10L)
                .runId("run-42")
                .n(1)
                .hypothesis(new HypothesisDto("H2", 0.75, "Tune connection pool in properties"))
                .change(new ChangeDto("edits", List.of(
                        new EditDto("src/main/resources/application.properties", "spring.datasource.hikari.maximum-pool-size=30\n"),
                        new EditDto("src/main/java/io/diag/Service.java", "class Service {}\n")
                ), null, null))
                .outcome("REVERTED")
                .keepType(null)
                .finding("Pool size increase did not reduce wait time.")
                .build();

        Document doc = factory.toDocument(iteration, run, "Load: 200.0 RPS, p95 1500ms (0.0% errors). Hotspots: ThreadPark");

        assertThat(doc.getText())
                .contains("PROPOSAL: custom-edit on src/main/resources/application.properties, src/main/java/io/diag/Service.java - Tune connection pool in properties (Hypothesis H2, Confidence 0.75)")
                .contains("OUTCOME: REVERTED (KeepType: NONE)");

        assertThat(doc.getMetadata())
                .containsEntry("template", "custom-edit")
                .containsEntry("keepType", "NONE")
                .containsEntry("outcome", "REVERTED");
        assertThat(doc.getMetadata().values()).doesNotContainNull();
    }

    @Test
    void toDocument_missingOptionalFields_usesNoneDefaultsWithZeroNullMetadata() {
        Run run = Run.builder().id(null).targetId("").build();
        Map<String, Object> ledgerWithNulls = new HashMap<>();
        ledgerWithNulls.put("deltaP95Ms", null);

        Iteration iteration = Iteration.builder()
                .n(1)
                .hypothesis(new HypothesisDto(null, 0.0, " "))
                .change(null)
                .outcome(null)
                .keepType(null)
                .finding(null)
                .ledger(ledgerWithNulls)
                .build();

        Document doc = factory.toDocument(iteration, run, "Load: 100 RPS");

        Map<String, Object> meta = doc.getMetadata();
        assertThat(meta.values()).doesNotContainNull();
        assertThat(meta)
                .containsEntry("runId", "NONE")
                .containsEntry("targetId", "NONE")
                .containsEntry("category", "NONE")
                .containsEntry("template", "NONE")
                .containsEntry("outcome", "NONE")
                .containsEntry("keepType", "NONE");
    }

    @Test
    void toDocument_nullArguments_throwsIllegalArgumentException() {
        Run run = Run.builder().id("r1").targetId("t1").build();
        Iteration iter = Iteration.builder().n(1).hypothesis(new HypothesisDto("H1", 0.5, "r")).build();

        assertThatThrownBy(() -> factory.toDocument(null, run, "symptom"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.toDocument(iter, null, "symptom"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> factory.toDocument(iter, run, "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void memoryConfig_validatesPreconditionsFailFast() {
        assertThatThrownBy(() -> new MemoryConfig(true, false, null, 0.70, 3, 1))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new MemoryConfig(true, false, "  ", 0.70, 3, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryConfig(true, false, "model", 0.0, 3, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryConfig(true, false, "model", 1.01, 3, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryConfig(true, false, "model", 0.70, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MemoryConfig(true, false, "model", 0.70, 3, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
