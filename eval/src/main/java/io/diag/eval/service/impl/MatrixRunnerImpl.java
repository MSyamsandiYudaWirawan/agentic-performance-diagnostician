package io.diag.eval.service.impl;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.BaselineCache;
import io.diag.agent.loop.DecideTurn;
import io.diag.agent.loop.LoopConfig;
import io.diag.agent.loop.TargetPipeline;
import io.diag.eval.model.MatrixScoreReport;
import io.diag.eval.service.AgentLoopFactory;
import io.diag.eval.service.EvalScorer;
import io.diag.eval.service.MatrixRunner;
import io.diag.evidence.entity.Target;
import io.diag.evidence.service.EvidenceService;
import io.diag.evidence.service.TargetRegistry;
import io.diag.runner.service.ChangeApplier;
import io.diag.runner.service.SeededTarget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Default implementation of MatrixRunner (§6.1, Step 11 M3, TigerStyle compliant).
 * Coordinates sequential evaluation across targets S1–S4.
 */
@Service
@Slf4j
public class MatrixRunnerImpl implements MatrixRunner {

    private final TargetRegistry targetRegistry;
    private final SeededTarget seededTarget;
    private final EvalScorer evalScorer;
    private final AgentLoopFactory agentLoopFactory;

    /**
     * Primary constructor with explicit AgentLoopFactory.
     */
    public MatrixRunnerImpl(TargetRegistry targetRegistry,
                            SeededTarget seededTarget,
                            EvalScorer evalScorer,
                            AgentLoopFactory agentLoopFactory) {
        this.targetRegistry = Objects.requireNonNull(targetRegistry, "targetRegistry must not be null");
        this.seededTarget = seededTarget;
        this.evalScorer = Objects.requireNonNull(evalScorer, "evalScorer must not be null");
        this.agentLoopFactory = Objects.requireNonNull(agentLoopFactory, "agentLoopFactory must not be null");
    }

    /**
     * Component constructor with standard service dependencies.
     */
    public MatrixRunnerImpl(TargetRegistry targetRegistry,
                            Optional<SeededTarget> seededTarget,
                            EvalScorer evalScorer,
                            EvidenceService evidenceService,
                            ChangeApplier changeApplier,
                            DecideTurn decideTurn,
                            TargetPipeline targetPipeline,
                            Optional<BaselineCache> baselineCache) {
        this(
                targetRegistry,
                seededTarget.orElse(null),
                evalScorer,
                (targetId, targetRepo, loopConfig, genParams) -> new AgentLoop(
                        evidenceService,
                        changeApplier,
                        decideTurn,
                        targetPipeline,
                        loopConfig,
                        genParams,
                        targetRepo,
                        targetId,
                        baselineCache.orElse(null)
                )
        );
    }

    @Override
    public MatrixScoreReport runMatrix(List<String> targetIds, LoopConfig loopConfig, GenParams genParams) throws Exception {
        Objects.requireNonNull(targetIds, "targetIds must not be null");
        Objects.requireNonNull(loopConfig, "loopConfig must not be null");
        Objects.requireNonNull(genParams, "genParams must not be null");

        if (targetIds.isEmpty()) {
            log.info("Empty targetIds provided to runMatrix; returning empty score report");
            return new MatrixScoreReport(0, 0, 0.0, 0, 0.0, List.of());
        }

        List<String> runIds = new ArrayList<>(targetIds.size());

        for (String targetId : targetIds) {
            Objects.requireNonNull(targetId, "targetId in targetIds must not be null");
            log.info("[MatrixRunner] Preparing target {}", targetId);

            Target target = targetRegistry.findById(targetId)
                    .orElseThrow(() -> new IllegalArgumentException("Target not found in registry: " + targetId));

            Path targetRepo = Path.of(target.getBaseRepo());

            if (seededTarget != null && target.getBaselineSha() != null) {
                Path patchPath = target.getSeedPatch() != null ? Path.of(target.getSeedPatch()) : null;
                log.info("[MatrixRunner] Seeding target {} (baseRepo={}, patch={})",
                        targetId, targetRepo, patchPath);
                seededTarget.seed(targetRepo, target.getBaselineSha(), patchPath, target.getId(), target.getName());
            }

            log.info("[MatrixRunner] Launching AgentLoop for target {}", targetId);
            AgentLoop loop = agentLoopFactory.create(targetId, targetRepo, loopConfig, genParams);
            String runId = loop.start();
            log.info("[MatrixRunner] Target {} finished with runId={}", targetId, runId);
            runIds.add(runId);
        }

        log.info("[MatrixRunner] All {} targets completed. Scoring runs: {}", runIds.size(), runIds);
        return evalScorer.scoreRuns(runIds);
    }
}
