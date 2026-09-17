package io.diag.evidence;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap for target-runner's integration gates (step-4 verify
 * gate). Lives in io.diag.evidence — NOT io.diag.runner — deliberately:
 * Spring Data JDBC repository scanning follows the app class's package
 * (AutoConfigurationPackages), and scanBasePackages on a runner-package app
 * class does NOT redirect it (paid for once: EvidenceServiceImpl wired but
 * zero repository beans). Same pattern as evidence's own TestApp, different
 * name to avoid an FQN collision on the shared test classpath.
 */
@SpringBootApplication
public class RunnerGateApp {
}
