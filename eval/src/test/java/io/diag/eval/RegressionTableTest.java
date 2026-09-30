package io.diag.eval;

import io.diag.eval.model.RegressionKey;
import io.diag.eval.model.RegressionRow;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.impl.RegressionTableServiceImpl;
import io.diag.evidence.entity.Run;
import io.diag.evidence.repository.RunRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Step 11 Milestone 1 Verify Gate (RegressionTableTest).
 * Tests grouping by (prompt_hash, model, aggregator) x target, aggregate statistics, and Markdown table output.
 */
public class RegressionTableTest {

    private RunRepository runRepository;
    private EvalScorer evalScorer;
    private RegressionTableServiceImpl tableService;

    @BeforeEach
    void setUp() {
        runRepository = Mockito.mock(RunRepository.class);
        evalScorer = Mockito.mock(EvalScorer.class);
        tableService = new RegressionTableServiceImpl(runRepository, evalScorer);
    }

    @Test
    void computeTable_groupsDifferentPromptsIntoDistinctRows() {
        String r1 = "run-prompt-A";
        String r2 = "run-prompt-B";

        Run run1 = Run.builder().id(r1).targetId("S1").promptHash("hashAAA111").model("gpt-4o").aggregatorVersion("1.0").build();
        Run run2 = Run.builder().id(r2).targetId("S1").promptHash("hashBBB222").model("gpt-4o").aggregatorVersion("1.0").build();

        when(runRepository.findById(r1)).thenReturn(Optional.of(run1));
        when(runRepository.findById(r2)).thenReturn(Optional.of(run2));

        RunScore score1 = new RunScore(r1, "S1", "COMPLETED", "H5", "H5", true, 2500, 2000, 500, 100, true, 180, 250, 70, 10, true, 1, 1000, BigDecimal.ONE, Instant.now(), Instant.now());
        RunScore score2 = new RunScore(r2, "S1", "COMPLETED", "H5", "H1", false, 2500, 2600, -100, 100, false, 180, 175, -5, 10, false, 2, 2000, BigDecimal.valueOf(2), Instant.now(), Instant.now());

        when(evalScorer.scoreRun(r1)).thenReturn(score1);
        when(evalScorer.scoreRun(r2)).thenReturn(score2);

        List<RegressionRow> rows = tableService.computeTable(List.of(r1, r2));

        assertThat(rows).hasSize(2);

        RegressionRow rowA = rows.get(0);
        assertThat(rowA.key().promptHash()).isEqualTo("hashAAA111");
        assertThat(rowA.runCount()).isEqualTo(1);
        assertThat(rowA.accuracyRate()).isEqualTo(1.0);
        assertThat(rowA.convergenceRate()).isEqualTo(1.0);
        assertThat(rowA.meanP95DeltaMs()).isEqualTo(500.0);

        RegressionRow rowB = rows.get(1);
        assertThat(rowB.key().promptHash()).isEqualTo("hashBBB222");
        assertThat(rowB.runCount()).isEqualTo(1);
        assertThat(rowB.accuracyRate()).isEqualTo(0.0);
        assertThat(rowB.convergenceRate()).isEqualTo(0.0);
        assertThat(rowB.meanP95DeltaMs()).isEqualTo(-100.0);

        // Test Markdown output
        String md = tableService.renderMarkdown(rows);
        assertThat(md).contains("`hashAAA1`");
        assertThat(md).contains("`hashBBB2`");
        assertThat(md).contains("100.0%");
        assertThat(md).contains("+500.0 ms");
    }

    @Test
    void computeTable_groupsSamePromptIntoSingleRowWithAggregates() {
        String r1 = "run-1";
        String r2 = "run-2";

        Run run1 = Run.builder().id(r1).targetId("S1").promptHash("hashSAME").model("glm-5.3").aggregatorVersion("1.0").build();
        Run run2 = Run.builder().id(r2).targetId("S1").promptHash("hashSAME").model("glm-5.3").aggregatorVersion("1.0").build();

        when(runRepository.findById(r1)).thenReturn(Optional.of(run1));
        when(runRepository.findById(r2)).thenReturn(Optional.of(run2));

        RunScore score1 = new RunScore(r1, "S1", "COMPLETED", "H5", "H5", true, 2500, 2000, 500, 100, true, 180, 250, 70, 10, true, 1, 1000, BigDecimal.ONE, Instant.now(), Instant.now());
        RunScore score2 = new RunScore(r2, "S1", "COMPLETED", "H5", "H5", true, 2500, 2100, 400, 100, true, 180, 240, 60, 10, true, 2, 1500, BigDecimal.valueOf(3), Instant.now(), Instant.now());

        when(evalScorer.scoreRun(r1)).thenReturn(score1);
        when(evalScorer.scoreRun(r2)).thenReturn(score2);

        List<RegressionRow> rows = tableService.computeTable(List.of(r1, r2));

        assertThat(rows).hasSize(1);
        RegressionRow row = rows.get(0);
        assertThat(row.runCount()).isEqualTo(2);
        assertThat(row.accuracyRate()).isEqualTo(1.0);
        assertThat(row.convergenceRate()).isEqualTo(1.0);
        assertThat(row.meanP95DeltaMs()).isEqualTo(450.0); // (500 + 400) / 2
        assertThat(row.meanRpsDelta()).isEqualTo(65.0);   // (70 + 60) / 2
        assertThat(row.meanIterations()).isEqualTo(1.5); // (1 + 2) / 2
        assertThat(row.meanTokens()).isEqualTo(1250L);   // (1000 + 1500) / 2
    }

    @Test
    void tigerStyle_failFastOnNulls() {
        assertThatThrownBy(() -> tableService.computeTable(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> tableService.renderMarkdown(null))
                .isInstanceOf(NullPointerException.class);
    }
}
