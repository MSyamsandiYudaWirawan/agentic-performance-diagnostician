package io.diag.agent.tools;

import io.diag.agent.ToolEnvelope;
import io.diag.agent.memory.DiagnosticMemoryRetriever;
import io.diag.agent.memory.MemoryConfig;
import io.diag.agent.memory.MemoryContextFormatter;
import io.diag.agent.memory.MemoryQuery;
import io.diag.evidence.dto.ChangeDto;
import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.JfrReport;
import io.diag.runner.config.ChangeResult;
import io.diag.runner.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * The four tools (§4.1, §10.1): thin adapters over runner services.
 * Every tool returns ToolEnvelope — the model always gets parseable JSON (§10.4).
 * <p>
 * Boundary: applyChange applies + commits only.
 * Rebuild → smoke → benchmark is loop orchestration (§4.2), not the tool.
 */
public class DiagnosticTools {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticTools.class);

    // §10.2: 100 KB cap on readSource
    private static final int READ_CAP_BYTES = 100 * 1024;

    private final BenchmarkRunner benchmarkRunner;
    private final JfrCapture jfrCapture;
    private final JfrAnalyzer jfrAnalyzer;
    private final ChangeApplier changeApplier;
    private final Path targetRoot;
    private final String currentTargetId;
    private final MemoryConfig memoryConfig;
    private final DiagnosticMemoryRetriever memoryRetriever;
    private final MemoryContextFormatter memoryFormatter;

    // previous JfrReportDto for diff — §10.32: loop sets this to the current kept state's
    // most recent recording before each iteration. Step 9 wires this; step 8 exposes the setter.
    private volatile JfrReportDto previousJfrReport;

    public DiagnosticTools(BenchmarkRunner benchmarkRunner,
                           JfrCapture jfrCapture,
                           JfrAnalyzer jfrAnalyzer,
                           ChangeApplier changeApplier,
                           Path targetRoot) {
        this(benchmarkRunner, jfrCapture, jfrAnalyzer, changeApplier, targetRoot, "test-target", null, null, null);
    }

    public DiagnosticTools(BenchmarkRunner benchmarkRunner,
                           JfrCapture jfrCapture,
                           JfrAnalyzer jfrAnalyzer,
                           ChangeApplier changeApplier,
                           Path targetRoot, String currentTargetId, MemoryConfig memoryConfig, DiagnosticMemoryRetriever memoryRetriever, MemoryContextFormatter memoryContextFormatter) {
        this.benchmarkRunner = benchmarkRunner;
        this.jfrCapture = jfrCapture;
        this.jfrAnalyzer = jfrAnalyzer;
        this.changeApplier = changeApplier;
        this.targetRoot = targetRoot;
        this.currentTargetId = currentTargetId;
        this.memoryConfig = memoryConfig;
        this.memoryRetriever = memoryRetriever;
        this.memoryFormatter = memoryContextFormatter;
    }

    public Path getTargetRoot() {
        return targetRoot;
    }

    public void setPreviousJfrReport(JfrReportDto previous) {
        this.previousJfrReport = previous;
    }

    /**
     * Lists relative paths of production and configuration files in the target repository.
     * Excludes VCS, build outputs, tests, binary assets, and editor directories.
     */
    public List<String> listRepositoryFiles() {
        if (targetRoot == null || !Files.exists(targetRoot)) {
            return List.of();
        }
        Path root = targetRoot.toAbsolutePath().normalize();
        List<String> files = new ArrayList<>();
        try (var stream = Files.walk(root, 8)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                if (!Files.isRegularFile(path)) {
                    continue;
                }
                Path relPath = root.relativize(path.toAbsolutePath().normalize());
                String relStr = relPath.toString().replace('\\', '/');
                if (shouldIncludeFile(relStr)) {
                    files.add(relStr);
                    if (files.size() >= 150) {
                        files.add("... (remaining files omitted)");
                        break;
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to walk repository files at {}: {}", targetRoot, e.getMessage());
            return List.of();
        }
        Collections.sort(files);
        return files;
    }

    private static boolean shouldIncludeFile(String path) {
        if (path.startsWith(".git")
                || path.startsWith("target/")
                || path.startsWith("build/")
                || path.startsWith(".mvn/")
                || path.startsWith("gradle/")
                || path.startsWith(".gradle/")
                || path.startsWith(".idea/")
                || path.startsWith(".vscode/")
                || path.startsWith(".devcontainer/")
                || path.startsWith(".github/")
                || path.startsWith("src/test/")) {
            return false;
        }
        if (path.endsWith(".png") || path.endsWith(".jpg") || path.endsWith(".jpeg")
                || path.endsWith(".gif") || path.endsWith(".svg") || path.endsWith(".ico")
                || path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf")
                || path.endsWith(".eot") || path.endsWith(".jar") || path.endsWith(".class")) {
            return false;
        }
        return true;
    }

    // -------------------------------------------------------------------------

    @Tool(description = """
            Run a k6 benchmark against the target service and return a LoadReport.
            Returns ok=true with the report even when thresholds are breached (verdict=FAIL)
            or the smoke gate fails (verdict=NOT_TESTABLE) — those are data, not errors.
            Returns ok=false only on infrastructure failure (Docker/IO).
            Always call this after every applyChange to measure the effect.
            """)
    public ToolEnvelope<LoadReportDto> runBenchmark(String label) {
        try {
            // The entity row is persistence bookkeeping — the model sees the compact
            // payload, the same JSON stored verbatim as the load_report JSONB (§4.1).
            return ToolEnvelope.ok(benchmarkRunner.run(label, false).getPayload());
        } catch (Exception e) {
            return ToolEnvelope.fail("runBenchmark infrastructure failure: " + e.getMessage());
        }
    }

    @Tool(description = """
            Stop the target container (flushing JFR dumponexit), harvest the recording,
            and return a JfrReport with per-signal aggregates (count, p50/p95/p99, severity,
            top-N classes/frames) plus a diff vs the previous kept-state recording.
            Signals: JavaMonitorEnter (lock contention), ThreadPark (blocking/pool-wait),
            SocketRead/Write (I/O), GCPhasePause (GC), ExecutionSample (CPU hot methods,
            top-3 raw frames per stack), ObjectAllocationSample, ExceptionThrow.
            The diff base is the previous kept-state recording, tracked server-side
            (§10.32) — the diff is absent on the first iteration.
            """)
    public ToolEnvelope<JfrReportDto> captureAndAnalyzeJfr(String label) {
        try {
            JfrReport captured = jfrCapture.capture(label);
            JfrReportDto report = jfrAnalyzer.analyze(
                    Path.of(captured.getJfrPath()), previousJfrReport, captured.getRunId(), label);
            return ToolEnvelope.ok(report);
        } catch (Exception e) {
            return ToolEnvelope.fail("captureAndAnalyzeJfr failure: " + e.getMessage());
        }
    }

    @Tool(description = """
            Read a source file from the target repository. Path must be relative to the
            target root (e.g. src/main/java/Foo.java or Dockerfile.target).
            Returns ok=false if the path escapes the target root, is a symlink out,
            or exceeds the 100 KB cap. Use this to investigate the suspect call path
            before proposing a change — read source before every applyChange.
            """)
    public ToolEnvelope<String> readSource(String relativePath) {
        try {
            Path resolved = guardPath(relativePath);
            // Size check BEFORE reading — a huge file must be rejected, not slurped.
            long size = Files.size(resolved);
            if (size > READ_CAP_BYTES) {
                return ToolEnvelope.fail("readSource: file exceeds 100 KB cap (" + size + " bytes): " + relativePath);
            }
            byte[] bytes = Files.readAllBytes(resolved);
            return ToolEnvelope.ok(new String(bytes, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return ToolEnvelope.fail(e.getMessage());
        } catch (Exception e) {
            return ToolEnvelope.fail("readSource failure: " + e.getMessage());
        }
    }

    @Tool(description = """
            Apply a change to the target repository and commit it.
            Two forms:
              kind=edits: [{path, content}] — full-file replacement, no deletions, 400-line cap per file.
                Missing path creates the file; existing path replaces the whole file.
              kind=template: id=jar-unpack (the only admitted template — §10.35).
                Other template ids are rejected until hand-validated.
            Returns ok=true with ChangeResult (commitSha, filesTouched, javaTouched) on success.
            Returns ok=false with the rejection message on path escape, line-cap violation,
            unadmitted template, or no-op (byte-identical content).
            This tool applies + commits only. The loop handles rebuild → smoke → benchmark.
            """)
    public ToolEnvelope<ChangeResult> applyChange(String description, ChangeDto change) {
        try {
            ChangeResult result = changeApplier.apply(targetRoot, change, description);
            return ToolEnvelope.ok(result);
        } catch (TemplateNotAdmittedException e) {
            return ToolEnvelope.fail(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ToolEnvelope.fail(e.getMessage());
        } catch (Exception e) {
            return ToolEnvelope.fail("applyChange failure: " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // path guard — shared confinement policy (§10.2), mirrors ChangeApplier.validatePath
    // -------------------------------------------------------------------------

    private Path guardPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("path must not be blank");
        }
        if (Path.of(rawPath).isAbsolute()) {
            throw new IllegalArgumentException("path must be relative, got: " + rawPath);
        }
        Path root = targetRoot.toAbsolutePath().normalize();
        Path resolved = root.resolve(rawPath).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes target root (§10.2): " + rawPath);
        }
        Path probe = resolved;
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        Path anchor = (probe != null) ? probe : root;
        try {
            if (Files.isSymbolicLink(anchor) || !anchor.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalArgumentException("path resolves outside target root via symlink (§10.2): " + rawPath);
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("cannot resolve real path of " + rawPath, e);
        }
        return resolved;
    }

    @Tool(description = """
            Search episodic memory of past diagnostic runs across services for similar symptoms, bottlenecks, or fixes.
            Supports semantic search, structured metadata filtering, or hybrid search.
            At least one parameter must be provided.
            Returns past attempted actions, whether they were KEPT or REVERTED, and key findings.
            """)
    public ToolEnvelope<String> queryPastFindings(
            @ToolParam(description = "Suspect symptom, bottleneck, or mechanism (e.g. 'lock contention on classloader' or 'Hikari connection pool timeout')", required = false)
            String symptomQuery,
            @ToolParam(description = "Diagnostic hypothesis category: H1 (CPU), H2 (IO/Pool), H3 (GC/Alloc), H4 (Lock/Sync), H5 (Classload), H6 (Config), H7 (Custom)", required = false)
            String category,
            @ToolParam(description = "Action template name (e.g. 'hikari-pool-size', 'jar-unpack', 'jvm-opts')", required = false)
            String template,
            @ToolParam(description = "Outcome filter: 'KEPT' (only successful fixes) or 'REVERTED' (failed attempts)", required = false)
            String outcome
    ) {
        if (!memoryConfig.enabled()) {
            return ToolEnvelope.ok("Episodic memory is disabled for this run.");
        }

        // Guard 1: currentTargetId is server-side context; never exposed as a parameter.
        // Guard 3: All-null DoS prevention (require at least 1 non-blank criterion)
        if (isNullOrBlank(symptomQuery) && isNullOrBlank(category) && isNullOrBlank(template) && isNullOrBlank(outcome)) {
            return ToolEnvelope.fail("At least one search criterion must be provided: symptomQuery, category, template, or outcome.");
        }

        String cleanSymptomQuery = isNullOrBlank(symptomQuery) ? null : symptomQuery.trim();
        String cleanCategory     = isNullOrBlank(category)     ? null : category.trim().toUpperCase();
        String cleanOutcome      = isNullOrBlank(outcome)      ? null : outcome.trim().toUpperCase();
        String cleanTemplate     = isNullOrBlank(template)     ? null : template.trim();

        if (!isNullOrBlank(symptomQuery) && cleanSymptomQuery.length() > 400) {
            return ToolEnvelope.fail("symptomQuery exceeds 400 character limit (" + symptomQuery.length() + " chars). Please provide a more focused query.");
        }

        if (!isNullOrBlank(category) && !cleanCategory.matches("^H[1-7]$")) {
            return ToolEnvelope.fail("Invalid category: '" + category + "'. Must be one of H1..H7 (e.g. H1=CPU, H2=IO, H3=GC, H4=Lock, H5=Classload, H6=Config, H7=Custom).");
        }
        if (!isNullOrBlank(outcome) && !Set.of("KEPT", "REVERTED").contains(cleanOutcome)) {
            return ToolEnvelope.fail("Invalid outcome: '" + outcome + "'. Must be 'KEPT' or 'REVERTED'.");
        }

        if (!isNullOrBlank(template) && cleanTemplate.length() > 50) {
            return ToolEnvelope.fail("template parameter exceeds maximum length of 50 characters.");
        }

        try {
            // Guard 4 (dynamic threshold) & eval-isolation are executed inside memoryRetriever
            MemoryQuery query = new MemoryQuery(cleanSymptomQuery, cleanCategory, cleanTemplate, cleanOutcome);
            List<Document> memories = memoryRetriever.retrieve(query, currentTargetId);

            if (memories.isEmpty()) {
                return ToolEnvelope.ok("No matching past diagnostic runs found for criteria: " + query.toCriteriaSummary());
            }
            String formatted = memoryFormatter.formatForPrompt(memories, currentTargetId);
            return ToolEnvelope.ok(formatted);
        } catch (Exception e) {
            log.warn("queryPastFindings failed: {}", e.getMessage());
            return ToolEnvelope.fail("Memory search temporarily unavailable: " + e.getMessage());
        }


    }

    private boolean isNullOrBlank(String s) {
        return s == null || s.isBlank();
    }

}
