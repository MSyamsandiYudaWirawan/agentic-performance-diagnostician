package io.diag.evidence;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap for agent-core's integration gate (step-8 verify gate).
 * Lives in io.diag.evidence deliberately — Spring Data JDBC repository scanning
 * follows the app class's package (the RunnerGateApp lesson): an agent-package
 * app class wires EvidenceServiceImpl with zero repository beans. It scans
 * only io.diag.evidence; io.diag.agent classes are constructed run-scoped in
 * the gate itself (the step-9 shape, §10.19).
 */
@SpringBootApplication
public class AgentGateApp {
}
