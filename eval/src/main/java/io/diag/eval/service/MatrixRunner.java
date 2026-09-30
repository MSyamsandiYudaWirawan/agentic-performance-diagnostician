package io.diag.eval.service;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.LoopConfig;
import io.diag.eval.model.MatrixScoreReport;

import java.util.List;

/**
 * Service contract for executing benchmark evaluation matrices (§6.1, Step 11 M3, TigerStyle compliant).
 * Coordinates sequential runs across targets (e.g. S1–S4) using TargetRegistry, SeededTarget,
 * AgentLoop, and BaselineCache, returning a scored MatrixScoreReport.
 */
public interface MatrixRunner {

    /**
     * Executes sequential evaluation runs across the specified targets.
     *
     * @param targetIds  list of target identifiers to execute (e.g. ["S1", "S2"])
     * @param loopConfig loop execution configuration
     * @param genParams  model generation parameters
     * @return aggregated MatrixScoreReport scoring all executed runs
     * @throws Exception if target preparation or execution fails
     */
    MatrixScoreReport runMatrix(List<String> targetIds, LoopConfig loopConfig, GenParams genParams) throws Exception;
}
