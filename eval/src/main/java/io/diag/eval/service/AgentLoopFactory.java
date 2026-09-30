package io.diag.eval.service;

import io.diag.agent.config.GenParams;
import io.diag.agent.loop.AgentLoop;
import io.diag.agent.loop.LoopConfig;

import java.nio.file.Path;

/**
 * Functional factory for instantiating an AgentLoop for a target during matrix evaluation (§6.1, Step 11 M3).
 */
@FunctionalInterface
public interface AgentLoopFactory {

    /**
     * Creates an AgentLoop instance for the given target and configuration.
     *
     * @param targetId   target identifier (e.g. S1, S2)
     * @param targetRepo target repository path
     * @param loopConfig loop execution configuration
     * @param genParams  model generation parameters
     * @return ready AgentLoop instance
     */
    AgentLoop create(String targetId, Path targetRepo, LoopConfig loopConfig, GenParams genParams);
}
