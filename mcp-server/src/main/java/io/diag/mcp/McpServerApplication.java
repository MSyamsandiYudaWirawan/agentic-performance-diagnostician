package io.diag.mcp;

import io.diag.agent.memory.MemoryConfig;
import io.diag.agent.memory.impl.DiagnosticMemoryRetrieverImpl;
import io.diag.agent.memory.impl.MemoryContextFormatterImpl;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;

/**
 * Boot entry point for the MCP Diagnostic Knowledge Server — the first production
 * (non-test-tree) {@code @SpringBootApplication} in the repo. Runs standalone on
 * port 8085 against the same PostgreSQL 17 + pgvector instance as agent-core.
 *
 * Wiring decisions:
 * <ul>
 *   <li>Scan is deliberately {@code io.diag.mcp + io.diag.evidence}, NOT all of
 *       {@code io.diag}: agent-core's {@code DiagnosticTools} is a run-scoped
 *       component (needs BenchmarkRunner/ChangeApplier/JfrCapture constructed
 *       per run) and must not be instantiated in this long-lived server context.</li>
 *   <li>{@code @EnableJdbcRepositories} is explicit because Spring Data repository
 *       discovery follows the <em>app-class</em> package (io.diag.mcp), not
 *       {@code scanBasePackages} — the step-4 RunnerGateApp lesson.</li>
 *   <li>The two memory collaborators live in agent-core, outside both scanned
 *       packages, so they are imported by class. Their {@code @Component}
 *       markers are inert here (no scan reaches them) — the import is what
 *       registers them, with {@code MemoryConfig} bound from {@code diag.memory.*}.</li>
 *   <li>VectorStore (PgVectorStore) and the OpenAI-protocol EmbeddingModel come
 *       from auto-configuration, configured by the {@code application.properties}
 *       shipped inside the agent-core jar (embedding endpoint/key carry offline
 *       defaults; only an actual embed call needs a real key).</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {"io.diag.mcp", "io.diag.evidence"})
@EnableJdbcRepositories(basePackages = "io.diag.evidence.repository")
@EnableConfigurationProperties(MemoryConfig.class)
@Import({DiagnosticMemoryRetrieverImpl.class, MemoryContextFormatterImpl.class})
public class McpServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServerApplication.class, args);
    }
}
