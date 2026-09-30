package io.diag.eval;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.LoopConfig;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.model.RunScore;
import io.diag.eval.service.AgentLoopFactory;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.impl.MatrixRunnerImpl;
import io.diag.evidence.entity.Target;
import io.diag.evidence.service.TargetRegistry;
import io.diag.runner.service.SeededTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Step 11 Milestone 3 Verify Gate (MatrixRunnerTest).
 * Tests sequential matrix orchestration across canonical targets with fake loop factory.
 */
public class MatrixRunnerTest {

    private TargetRegistry targetRegistry;
    private SeededTarget seededTarget;
    private EvalScorer evalScorer;
    private AgentLoopFactory loopFactory;
    private MatrixRunnerImpl matrixRunner;

    private final LoopConfig loopConfig = new LoopConfig(
            3, 30_000L, 500_000L,
            new BigDecimal("5.00"), 5, 0.50,
            BigDecimal.ZERO, BigDecimal.ZERO
    );
    private final GenParams genParams = new GenParams("anthropic", "claude-3-7-sonnet", 0.0, 2000);

    @BeforeEach
    void setUp() {
        targetRegistry = Mockito.mock(TargetRegistry.class);
        seededTarget = Mockito.mock(SeededTarget.class);
        evalScorer = Mockito.mock(EvalScorer.class);
        loopFactory = Mockito.mock(AgentLoopFactory.class);

        matrixRunner = new MatrixRunnerImpl(targetRegistry, seededTarget, evalScorer, loopFactory);
    }

    @Test
    void runMatrix_sequentialExecutionAcrossTargets() throws Exception {
        Target s1 = Target.builder()
                .id("S1")
                .name("Stock Spring Petclinic")
                .baseRepo("targets/spring-petclinic")
                .baselineSha("sha-s1")
                .seedPatch(null)
                .build();

        Target s2 = Target.builder()
                .id("S2")
                .name("PetClinic Hikari Pool Starvation")
                .baseRepo("targets/spring-petclinic")
                .baselineSha("sha-s1")
                .seedPatch("benchmarks/seeds/S2-hikari.patch")
                .build();

        when(targetRegistry.findById("S1")).thenReturn(Optional.of(s1));
        when(targetRegistry.findById("S2")).thenReturn(Optional.of(s2));

        AgentLoop loopS1 = Mockito.mock(AgentLoop.class);
        when(loopS1.start()).thenReturn("run-s1-id");

        AgentLoop loopS2 = Mockito.mock(AgentLoop.class);
        when(loopS2.start()).thenReturn("run-s2-id");

        when(loopFactory.create(eq("S1"), eq(Path.of("targets/spring-petclinic")), eq(loopConfig), eq(genParams)))
                .thenReturn(loopS1);
        when(loopFactory.create(eq("S2"), eq(Path.of("targets/spring-petclinic")), eq(loopConfig), eq(genParams)))
                .thenReturn(loopS2);

        Instant now = Instant.now();
        RunScore score1 = new RunScore(
                "run-s1-id", "S1", "COMPLETED", "H5", "H5", true,
                150.0, 110.0, -40.0, 20.0, true,
                200.0, 260.0, 60.0, 10.0, true,
                1, 25000L, new BigDecimal("0.25"),
                now, now
        );
        RunScore score2 = new RunScore(
                "run-s2-id", "S2", "COMPLETED", "H2", "H2", true,
                300.0, 150.0, -150.0, 25.0, true,
                100.0, 220.0, 120.0, 10.0, true,
                1, 20000L, new BigDecimal("0.20"),
                now, now
        );
        MatrixScoreReport expectedReport = new MatrixScoreReport(
                2, 2, 1.0, 2, 1.0, List.of(score1, score2)
        );
        when(evalScorer.scoreRuns(List.of("run-s1-id", "run-s2-id"))).thenReturn(expectedReport);

        MatrixScoreReport actualReport = matrixRunner.runMatrix(List.of("S1", "S2"), loopConfig, genParams);

        assertThat(actualReport).isNotNull();
        assertThat(actualReport.totalTargets()).isEqualTo(2);
        assertThat(actualReport.accuracyRate()).isEqualTo(1.0);
        assertThat(actualReport.convergenceRate()).isEqualTo(1.0);
        assertThat(actualReport.passesV1Threshold()).isTrue();

        // Verify seededTarget was invoked for both targets
        verify(seededTarget).seed(eq(Path.of("targets/spring-petclinic")), eq("sha-s1"), eq(null), eq("S1"), eq("Stock Spring Petclinic"));
        verify(seededTarget).seed(eq(Path.of("targets/spring-petclinic")), eq("sha-s1"), eq(Path.of("benchmarks/seeds/S2-hikari.patch")), eq("S2"), eq("PetClinic Hikari Pool Starvation"));

        // Verify both loops started
        verify(loopS1).start();
        verify(loopS2).start();

        // Verify scoring called with both runIds
        verify(evalScorer).scoreRuns(List.of("run-s1-id", "run-s2-id"));
    }

    @Test
    void runMatrix_emptyTargetIds_returnsZeroReport() throws Exception {
        MatrixScoreReport report = matrixRunner.runMatrix(List.of(), loopConfig, genParams);
        assertThat(report.totalTargets()).isEqualTo(0);
        assertThat(report.accurateCount()).isEqualTo(0);
        assertThat(report.accuracyRate()).isEqualTo(0.0);
        assertThat(report.runScores()).isEmpty();
    }

    @Test
    void runMatrix_unregisteredTarget_throwsIllegalArgumentException() {
        when(targetRegistry.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> matrixRunner.runMatrix(List.of("UNKNOWN"), loopConfig, genParams))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target not found in registry: UNKNOWN");
    }

    @Test
    void nullValidation_throwsOnNullArgs() {
        assertThatThrownBy(() -> matrixRunner.runMatrix(null, loopConfig, genParams))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> matrixRunner.runMatrix(List.of("S1"), null, genParams))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> matrixRunner.runMatrix(List.of("S1"), loopConfig, null))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> {
            List<String> listWithNull = new java.util.ArrayList<>();
            listWithNull.add(null);
            matrixRunner.runMatrix(listWithNull, loopConfig, genParams);
        }).isInstanceOf(NullPointerException.class);
    }
}
