package io.diag.evidence;

import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Test-only bootstrap: evidence is a library module and deliberately has no
 * main class — the test JVM is the app (build-steps step 1, Testcontainers
 * envelope). Lives at io.diag.evidence so component scan picks up everything
 * in the module.
 */
@SpringBootApplication
public class TestApp {
}
