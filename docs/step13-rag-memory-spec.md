# Step 13 Specification — RAG Episodic Memory Capstone

Companion to `v1.0-scope.md` (§4.4, §6), `schema.md`, `ai-ml-study-path.md` (Horizon 1 Capstone), and `TIGERSTYLE.md`.
Follows explicit failure modes, atomic documents, no silent swallows, and deterministically testable milestones (M0–M4).

---

## Part 0 — Goal & Mental Model

### Goal
Implement episodic vector memory for the autonomous diagnostic agent. Each historical iteration is vectorized as an atomic document containing the diagnostic symptom, proposal, outcome, and post-hoc finding, paired with rich metadata. The system supports two operational modes:
1. **Eval / Benchmark Mode**: Enforces cross-target retrieval (`targetId != currentTargetId`) to prevent test-set answer leakage and scientifically measure general diagnostic transfer.
2. **Production Mode**: Allows same-target retrieval (`targetId == currentTargetId`) to provide institutional repository memory, acting as evidence-based advisories (not rigid bans) so the agent avoids repeating known dead-ends while retaining full freedom to reason when mechanisms differ.

### The Mental Model 

```
 ┌────────────────────────────────────────────────────────────────────────┐
 │                      ChatClient 1: Diagnostician                       │
 │           (Claude 3.5 Sonnet / GPT-4o via SpringAiChatPort)            │
 │                                                                        │
 │   Tools available during decide turn:                                  │
 │   - readSource(path)                                                   │
 │   - runBenchmark(label)                                                │ 
 │   - captureAndAnalyzeJfr(label)                                        │
 │   - applyChange(description, change)                                   │
 │   - queryPastFindings(...) [Unified Hybrid Tool] ───┐                      │
 └─────────────────────────────────────────────────┼──────────────────────┘
                                                   │ LLM executes tool
                                                   ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │          DiagnosticMemoryTool / DiagnosticMemoryRetriever              │
 │                                                                        │
 │  Mode 1 (Benchmark): targetId != currentTargetId (Zero Leakage)        │
 │  Mode 2 (Production): All targets allowed, with Evidence Advisories:   │
 │     - [CAUTION - Same Service]: Reverted earlier (discourage repeat)   │
 │     - [OBSERVATION - Other Service]: Reverted on other repo (context)  │
 │     - [BASELINE - Same Service]: Kept earlier (verify if active)       │
 │     - [PROVEN PATTERN - Other Service]: Kept on other repo (precedent) │
 │  Diversity: Deduplicates by (targetId, template)                       │
 └─────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │          ChatClient 2 / RAG Subsystem (Embedding + pgvector)           │
 │                                                                        │
 │   - EmbeddingModel generates dense vector (1536-dim)                   │
 │   - Spring AI PgVectorStore queries PostgreSQL 17 (pgvector)           │
 │   - Unit Tests use in-memory SimpleVectorStore                         │
 └─────────────────────────────────┬──────────────────────────────────────┘
                                   │
                                   ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │                         Evidence DB (Postgres)                         │
 │     `vector_store` table (HNSW Cosine Index) + `iteration` history     │
 └────────────────────────────────────────────────────────────────────────┘
```

---

## Part 1 — Key Architectural Decisions

### D1: Iteration-Level Granularity (Atomic Document, No Splitter)
- **Granularity**: 1 `Iteration` = 1 `Document` (`org.springframework.ai.document.Document`).
- **No `TokenTextSplitter`**: Diagnostic attempts are compact episodes (~150–350 tokens). Chunking would sever the causal link between `SYMPTOM` and `FINDING`.
- **Atomic Case-Based Reasoning**: Each document represents a complete problem-action-outcome episode.

### D2: Asymmetric Symptom-Forward Text Representation
At retrieval time, the agent only knows the **current symptoms** (it does not know the solution or outcome yet). To maximize cosine similarity with the query vector, the document content is structured with consistent uppercase section headers, leading with the symptom:

```text
SYMPTOM: [Top JFR lock/GC/pool hotspots, p95 latency, RPS, thread states]
PROPOSAL: [Template name / Code edit intent] (Hypothesis [H1-H7], Confidence [0.0-1.0])
OUTCOME: [KEPT | REVERTED | WASTED] (KeepType: [RPS | P95 | MECHANISM | NONE])
FINDING: [One-sentence post-hoc evidence finding]
```

> **Note on Hypothesis Taxonomy**: H1–H7 is the full domain taxonomy defined in `SystemPrompts.java` and `HypothesisDto.java` (`H1 external I/O`, `H2 pool/parking`, `H3 GC`, `H4 CPU`, `H5 lock`, `H6 allocation`, `H7 exceptions`). Benchmark targets S1–S4 in v1.0 specifically test seeds H2–H5, while H1, H6, H7 are forward-looking extensions.

### D3: Full Structured Data & Model Versioning in Metadata
Vector text is kept clean and dense. All relational keys, filters, and numeric signals live in `Document.getMetadata()`:
* `runId` (String): ID of the originating run.
* `iterationN` (Integer): 1 to 5.
* `targetId` (String): e.g. `petclinic-s1`, `practice-mvc-s4`.
* `category` (String): `H1`–`H7`.
* `template` (String): e.g. `jar-unpack`, `hikari-pool-size`, `jvm-opts`, or `custom-edit`.
* `outcome` (String): `KEPT`, `REVERTED`, `WASTED`.
* `keepType` (String): `RPS`, `P95`, `MECHANISM`, `NONE`.
* `deltaP95Ms` (Double): Latency delta achieved by this change.
* `deltaRps` (Double): Throughput delta achieved by this change.
* `embeddingModel` (String): e.g. `text-embedding-3-small`. Guards against cross-model vector space contamination when switching models in Stage 2.
* `schemaVersion` (Integer): Currently `1`. Protects against stale document schema formats.

### D4: Dual Operational Modes via Metadata Filtering

#### Mode 1: Benchmark / Evaluation Mode (`isEvaluationMode = true`)
- **Rule**: Strict cross-target retrieval only.
- **Filter**: `FilterExpressionBuilder.ne("targetId", currentTargetId)` AND `b.eq("embeddingModel", currentModel)`.
- **Rationale**: Prevents train/test data leakage. An agent being benchmarked on `petclinic-s1` must not retrieve past runs of `petclinic-s1`; it must solve the problem using general diagnostic heuristics learned across other targets.

#### Mode 2: Production Mode (`isEvaluationMode = false`)
- **Rule**: All past runs accessible, including the current target.
- **Filter**: `b.eq("embeddingModel", currentModel)`.
- **Evidence-Based Advisory System (Non-Dogmatic)**:
  Rather than rigid bans (`DO NOT RE-ATTEMPT`) that blind the model when identical symptoms stem from different root causes, the formatter applies a contextual 2x2 advisory matrix:
  1. `targetId == currentTargetId && outcome == REVERTED`: Decorated with `[CAUTION - Same Service]` — warns that this proposal previously failed on this repo and provides the exact finding, strongly discouraging repetition unless the agent identifies clear evidence that conditions changed.
  2. `targetId != currentTargetId && outcome == REVERTED`: Decorated with `[OBSERVATION - Other Service]` — notes that this proposal was reverted on another repo, reminding the model that different root causes may apply to its target.
  3. `targetId == currentTargetId && outcome == KEPT`: Decorated with `[BASELINE - Same Service]` — notes that this fix was already merged into the current codebase's baseline, prompting the agent to verify if it is already active before re-proposing.
  4. `targetId != currentTargetId && outcome == KEPT`: Decorated with `[PROVEN PATTERN - Other Service]` — highlights a proven optimization recipe that succeeded under similar symptoms on another service.

### D5: Deduplication & Diversity Constraint
Nearest-neighbor vector search can return multiple iterations from the same target testing the same proposal.
* **Diversity Rule**: The retriever deduplicates retrieved candidates by `(targetId, template)`. If multiple records share the same target and template, only the highest-scoring candidate is retained.
* This ensures the top-K budget (default 3) delivers diverse perspectives rather than 3 identical warnings.

### D6: Agentic Tool-Based Retrieval & Unified Hybrid Search
Rather than blindly stuffing past iterations into the prompt context on every single turn (passive RAG), episodic memory is queried on-demand via a single, unified hybrid tool: `queryPastFindings(...)`.

#### Why 1 Unified Tool instead of 2 (Semantic + Metadata)?
Separating into two tools (`queryPastFindings` and `filterMetadataFindings`) induces LLM choice paralysis, doubles prompt token overhead, and forbids hybrid queries ("find lock contention symptoms where outcome was KEPT"). With one unified tool, the agent can perform:
1. **Pure Semantic Search**: `queryPastFindings(symptomQuery="lock contention on ClassLoader")`
2. **Filtered Hybrid Search**: `queryPastFindings(symptomQuery="lock contention", outcome="KEPT")`
3. **Pure Metadata Audit**: `queryPastFindings(category="H3", outcome="KEPT")` (no text query)

#### The 5 Essential Metadata Query Guards
Allowing an LLM to supply filter parameters introduces critical security, evaluation, and vector space failure modes. Five strict server-side guards are enforced within the tool layer:
1. **Target Isolation & Eval-Bypass Guard (`currentTargetId` Server-Side Hardcoded)**:
   - *Risk*: If `targetId` were exposed as a tool parameter, an LLM could query its own target during benchmarks or snoop across boundaries.
   - *Defense*: `targetId` is **never exposed** to the LLM's tool interface. In benchmark mode (`isEvaluationMode = true`), the tool hard-enforces `b.ne("targetId", currentTargetId)` regardless of LLM parameters. In production mode, target-awareness is handled internally by the advisory formatter.
2. **Whitelist & Enum Validation Guard (Fail-Fast on Hallucinations)**:
   - *Risk*: LLMs hallucinate invalid filter values (e.g. `category="database"`, `outcome="SUCCESS"`), causing silent empty matches or database query errors.
   - *Defense*: Validated strictly before query dispatch:
     - `category`: Must match `^H[1-7]$` or be null. If invalid, returns `ToolEnvelope.fail("Invalid category: '" + category + "'. Must be one of H1..H7 (e.g. H1=CPU, H2=IO, H3=GC, H4=Lock, H5=Classload, H6=Config, H7=Custom).")`.
     - `outcome`: Normalized to uppercase and must equal `"KEPT"` or `"REVERTED"` or be null. Any other value returns `ToolEnvelope.fail("Invalid outcome: '" + outcome + "'. Must be 'KEPT' or 'REVERTED'.")`.
3. **All-Null DoS & Unbounded Scan Guard**:
   - *Risk*: The LLM invokes the tool with all parameters null or empty (`symptomQuery=null, category=null, template=null, outcome=null`), attempting an accidental dump of all vector records.
   - *Defense*: The tool checks that at least one search criterion is non-blank. If all are blank, immediately returns `ToolEnvelope.fail("At least one search criterion must be provided: symptomQuery, category, template, or outcome.")`.
4. **Dynamic Similarity Threshold Guard (Pure Metadata vs. Hybrid Search)**:
   - *Risk*: For semantic or hybrid queries, `similarityThreshold = 0.70` prevents irrelevant vector noise. But for **pure metadata queries** (where `symptomQuery == null`), there is no semantic vector. If a dummy vector or default threshold were applied, 100% of valid metadata records would be filtered out by cosine distance!
   - *Defense (Unified Dummy-Vector Approach)*: When `symptomQuery` is absent, the retriever queries via `SearchRequest.builder().query("").similarityThreshold(0.0).filterExpression(filterExpression).topK(topK).build()`. Setting `similarityThreshold = 0.0` ensures uniform execution across both in-memory `SimpleVectorStore` (test seam) and PostgreSQL `PgVectorStore` (production) without code bifurcation or null vector crashes.
5. **Fail-Fast Length Caps & Input Sanitization Guard**:
   - *Risk*: Runaway LLM generation injecting thousands of characters or control sequences into filter fields. Silent truncation would violate TigerStyle Section 4 and mask query degradation from the model.
   - *Defense*: TigerStyle fail-fast validation. If `symptomQuery.length() > 400`, the tool immediately returns `ToolEnvelope.fail("symptomQuery exceeds 400 character limit (" + symptomQuery.length() + " chars). Please provide a more focused query.")`. Other parameters enforce strict caps (`category <= 10`, `template <= 50`, `outcome <= 10`), whitespace trimming, and sanitization.


### D7: PostgreSQL (`pgvector`) Storage & Migration
Episodic vectors live in the existing Evidence PostgreSQL database:
* **Docker Image**: `docker/evidence/docker-compose.yml` uses `pgvector/pgvector:pg17` (official PostgreSQL 17 + pgvector extension).
* **Flyway Migration (`V3__vector_store.sql`)**: Installs `vector` extension, creates `vector_store` table, and adds an HNSW index with `vector_cosine_ops`.
* **Testing Seam**: Unit tests (`M0`–`M3`) use `SimpleVectorStore` (fast, in-memory, no Docker). Production and integration tests (`M4`) use Spring AI's `PgVectorStore`.

### D8: Dual `ChatClient` Decoupling
* **`ChatClient 1` (Diagnostician)**: Drives the agent loop, reasons over code/JFR, and invokes tools via `SpringAiChatPort`.
* **`ChatClient 2` / RAG Pipeline (Embedding & Search)**: Owns the `EmbeddingModel` and `PgVectorStore`. If embedding models or query transformers are swapped, `ChatClient 1` is completely unaffected.

---

## Part 2 — Core Contracts & Configuration

### 1. Configuration Properties (`MemoryConfig`)

Adheres to `TIGERSTYLE.md` Section 4 (Fail-Fast, no hidden default mutation in constructors). Preconditions are validated explicitly. Default factory provided for tests and programmatic usage; production defaults bound via Spring Boot configuration properties.

```java
package io.diag.agent.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Objects;

@ConfigurationProperties(prefix = "diag.memory")
public record MemoryConfig(
        boolean enabled,
        boolean evaluationMode,
        String embeddingModel,          // e.g. "text-embedding-3-small" or "nomic-embed-text"
        double similarityThreshold,     // calibrated per model: 0.70 (OpenAI) / 0.55 (local models)
        int topK,                       // maximum diverse memories to inject (default: 3)
        int schemaVersion               // document format version (default: 1)
) {
    public MemoryConfig {
        Objects.requireNonNull(embeddingModel, "embeddingModel must not be null");
        if (embeddingModel.isBlank()) {
            throw new IllegalArgumentException("embeddingModel must not be blank");
        }
        if (similarityThreshold <= 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("similarityThreshold must be in (0.0, 1.0], got: " + similarityThreshold);
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("topK must be > 0, got: " + topK);
        }
        if (schemaVersion <= 0) {
            throw new IllegalArgumentException("schemaVersion must be > 0, got: " + schemaVersion);
        }
    }

    /**
     * Standard defaults for tests and baseline operation.
     */
    public static MemoryConfig defaults() {
        return new MemoryConfig(true, false, "text-embedding-3-small", 0.70, 3, 1);
    }
}
```

```properties
# application.properties defaults
diag.memory.enabled=true
diag.memory.evaluation-mode=false
diag.memory.embedding-model=text-embedding-3-small
diag.memory.similarity-threshold=0.70
diag.memory.top-k=3
diag.memory.schema-version=1

# Flyway placeholder default (1536 for text-embedding-3-small)
spring.flyway.placeholders.vector.dimension=1536
```

### 2. Symptom Summarizer Contract (Single Source of Truth)

To guarantee query and document embedding distribution alignment, the **exact same contract** must be used at ingestion time and retrieval time.
The size budget is defined as **$\le 400$ characters** (safe proxy for $\sim 60$–$80$ tokens), making it 100% testable in unit tests without external tokenizer dependencies.

```java
package io.diag.agent.memory;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;

public interface SymptomSummarizer {
    /**
     * Produces a compact, deterministic summary string (strictly <= 400 characters).
     *
     * Format:
     * "Load: <rps> RPS, p95 <p95>ms (<failRate>% errors). Hotspots: <Signal1> (<severity>, count=<N>, frames=[<f1>, <f2>]); <Signal2> (...)"
     *
     * Selection rules:
     * 1. Load: include raw RPS, p95 (ms rounded to integer), and error rate.
     * 2. JFR: Filter signals where severity is CRITICAL or CONCERNING. If none, take top-2 by count.
     * 3. Top frames: include up to 2 class/method frame strings per critical signal.
     * 4. Invariant: resulting string must not exceed 400 characters (truncated deterministically if necessary).
     */
    String summarize(LoadReportDto load, JfrReportDto jfr);
}
```

### 3. Document Factory Contract

```java
package io.diag.agent.memory;

import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;
import org.springframework.ai.document.Document;

public interface IterationDocumentFactory {
    /**
     * Converts an Evidence DB Iteration, its parent Run, and the symptom summary
     * into an atomic Document. No text splitting applied.
     */
    Document toDocument(Iteration iteration, Run run, String symptomSummary);
}
```

### 4. Episodic Memory Retriever Contract (Direct VectorStore Implementation)

Operates directly over Spring AI's `VectorStore` primitive via `vectorStore.similaritySearch(SearchRequest)` rather than the higher-level `DocumentRetriever` wrapper. `VectorStore` serves as the single symmetric abstraction for both writes (`add`) and reads (`similaritySearch`), supporting dynamic metadata filters with zero wrapper ceremony.

```java
package io.diag.agent.memory;

import org.springframework.ai.document.Document;
import java.util.List;

public interface DiagnosticMemoryRetriever {
    /**
     * Search criteria container for hybrid semantic and metadata retrieval.
     */
    record MemoryQuery(
            String symptomQuery,
            String category,
            String template,
            String outcome
    ) {
        public boolean hasSymptomQuery() {
            return symptomQuery != null && !symptomQuery.isBlank();
        }

        /**
         * Human/LLM-friendly summary of active criteria (avoids leaking Java record syntax).
         */
        public String toCriteriaSummary() {
            java.util.List<String> parts = new java.util.ArrayList<>();
            if (hasSymptomQuery()) parts.add("symptom='" + symptomQuery + "'");
            if (category != null) parts.add("category=" + category);
            if (template != null) parts.add("template=" + template);
            if (outcome != null) parts.add("outcome=" + outcome);
            return String.join(", ", parts);
        }
    }

    /**
     * Retrieves diverse past diagnostic experiences matching symptoms and/or metadata filters.
     * 
     * Operational parameters (topK, similarityThreshold, evaluationMode, embeddingModel)
     * are read directly from the injected MemoryConfig and executed directly via
     * VectorStore.similaritySearch(SearchRequest).
     *
     * Execution Semantics (Guard 4):
     * 1. Hybrid / Semantic (query.hasSymptomQuery() == true):
     *    SearchRequest.builder().query(query.symptomQuery()).similarityThreshold(config.similarityThreshold()).filterExpression(filter).topK(config.topK()).build()
     * 2. Pure Metadata (query.hasSymptomQuery() == false):
     *    SearchRequest.builder().query("").similarityThreshold(0.0).filterExpression(filter).topK(config.topK()).build()
     *    Note: MemoryConfig enforces similarityThreshold in (0.0, 1.0] for semantic queries to prevent misconfiguration.
     *    Guard 4 passes 0.0 directly to SearchRequest at runtime for pure metadata audits, ensuring uniform behavior
     *    across SimpleVectorStore and PgVectorStore without bifurcating code.
     *
     * @param query Structured query containing optional semantic text and metadata filters
     * @param currentTargetId The target currently being diagnosed (for eval isolation)
     * @return List of deduplicated, filtered memory documents (size <= config.topK())
     */
    List<Document> retrieve(MemoryQuery query, String currentTargetId);
}
```

### 5. Ingestion & Indexer Contract

```java
package io.diag.agent.memory;

import io.diag.evidence.dto.JfrReportDto;
import io.diag.evidence.dto.LoadReportDto;
import io.diag.evidence.entity.Iteration;
import io.diag.evidence.entity.Run;

public interface IterationIndexer {
    /**
     * Ingests a completed iteration into the VectorStore.
     * 
     * Ingestion rules:
     * 1. Only indexes iterations where outcome is KEPT or REVERTED.
     * 2. Skips ALL WASTED iterations unconditionally. Wasted iterations (malformed decision,
     *    compilation error, or smoke timeout) lack benchmark-verified evidence and must not
     *    pollute the memory store.
     * 3. Uses SymptomSummarizer and IterationDocumentFactory to generate the Document.
     * 4. Appends to the underlying VectorStore.
     * 
     * Ingestion Trigger Hook:
     * Called directly inside AgentLoop.java after KeepRule evaluates the cycle and
     * evidenceService.createIteration(...) writes the row to Postgres.
     */
    void index(Iteration iteration, Run run, LoadReportDto load, JfrReportDto jfr);
}
```

### 6. Memory Prompt Context Formatter

```java
package io.diag.agent.memory;

import org.springframework.ai.document.Document;
import java.util.List;

public interface MemoryContextFormatter {
    /**
     * Formats retrieved documents into the tool response text.
     * Each experience block begins with a standardized advisory header:
     *   [Experience N] [<ADVISORY_TAG>: <targetId>]
     * where <ADVISORY_TAG> is one of:
     *   - CAUTION - Same Service (Reverted on current target: discourage repeat without hard censor)
     *   - OBSERVATION - Other Service (Reverted on different target: informative context)
     *   - BASELINE - Same Service (Kept on current target: verify if already active in codebase)
     *   - PROVEN PATTERN - Other Service (Kept on different target: high-confidence precedent)
     * Followed by structured bullet fields:
     *   - SYMPTOM: ...
     *   - ACTION: ...
     *   - OUTCOME: ...
     *   - FINDING: ...
     *   - ADVISORY: <Contextual rationale and guidance>
     */
    String formatForPrompt(List<Document> memories, String currentTargetId);
}
```

### 7. Agentic Retrieval Tool Contract (`DiagnosticTools`)

Added to `io.diag.agent.tools.DiagnosticTools` as the 5th tool alongside `readSource`, `runBenchmark`, `captureAndAnalyzeJfr`, and `applyChange`.

> **Bean Lifecycle & Scoping**: In `agent-core`, `DiagnosticTools` is instantiated **per `AgentLoop` run** (taking the run-specific `Path targetRoot` and `String targetId` in its constructor, exactly as seen in `Step9RealRunGate.java`), rather than an unconfigured singleton. This guarantees `currentTargetId` is isolated per run with zero thread-safety contention.

```java
package io.diag.agent.tools;

import io.diag.agent.ToolEnvelope; // Existing wrapper from io.diag.agent
import io.diag.agent.memory.DiagnosticMemoryRetriever.MemoryQuery;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;
import java.util.Set;

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
    boolean hasSymptom = symptomQuery != null && !symptomQuery.isBlank();
    boolean hasCategory = category != null && !category.isBlank();
    boolean hasTemplate = template != null && !template.isBlank();
    boolean hasOutcome = outcome != null && !outcome.isBlank();

    if (!hasSymptom && !hasCategory && !hasTemplate && !hasOutcome) {
        return ToolEnvelope.fail("At least one search criterion must be provided: symptomQuery, category, template, or outcome.");
    }

    // Guard 5: Fail-fast length caps & string sanitization (TigerStyle Section 4: no silent truncation)
    if (hasSymptom && symptomQuery.length() > 400) {
        return ToolEnvelope.fail("symptomQuery exceeds 400 character limit (" + symptomQuery.length() + " chars). Please provide a more focused query.");
    }
    String cleanCategory = hasCategory ? category.trim().toUpperCase() : null;
    String cleanTemplate = hasTemplate ? template.trim() : null;
    String cleanOutcome = hasOutcome ? outcome.trim().toUpperCase() : null;

    // Guard 2: Whitelist & enum validation (fail fast on hallucinations)
    if (cleanCategory != null && !cleanCategory.matches("^H[1-7]$")) {
        return ToolEnvelope.fail("Invalid category: '" + category + "'. Must be one of H1..H7 (e.g. H1=CPU, H2=IO, H3=GC, H4=Lock, H5=Classload, H6=Config, H7=Custom).");
    }
    if (cleanOutcome != null && !Set.of("KEPT", "REVERTED").contains(cleanOutcome)) {
        return ToolEnvelope.fail("Invalid outcome: '" + outcome + "'. Must be 'KEPT' or 'REVERTED'.");
    }
    if (cleanTemplate != null && cleanTemplate.length() > 50) {
        return ToolEnvelope.fail("template parameter exceeds maximum length of 50 characters.");
    }

    try {
        // Guard 4 (dynamic threshold) & eval-isolation are executed inside memoryRetriever
        MemoryQuery query = new MemoryQuery(hasSymptom ? symptomQuery : null, cleanCategory, cleanTemplate, cleanOutcome);
        List<Document> memories = memoryRetriever.retrieve(query, currentTargetId);

        if (memories.isEmpty()) {
            return ToolEnvelope.ok("No matching past diagnostic runs found for criteria: " + query.toCriteriaSummary());
        }

        // Format with non-dogmatic evidence advisory tags
        String formatted = memoryFormatter.formatForPrompt(memories, currentTargetId);
        return ToolEnvelope.ok(formatted);
    } catch (Exception e) {
        log.warn("queryPastFindings failed: {}", e.getMessage());
        return ToolEnvelope.fail("Memory search temporarily unavailable: " + e.getMessage());
    }
}
```

### 8. PostgreSQL (`pgvector`) Migration & Docker Setup

#### Flyway Migration: `evidence/src/main/resources/db/migration/V3__vector_store.sql`
Dimension is parameterized via a Flyway placeholder with a default of 1536 (`text-embedding-3-small`). Stage 2 model swaps (e.g. 768-dim `nomic-embed-text`) are accommodated either by setting `flyway.placeholders.vector.dimension=768` before DB initialization or via a subsequent `V4__` migration.

```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY,
    content TEXT NOT NULL,
    metadata JSONB,
    embedding VECTOR(${vector.dimension})
);

CREATE INDEX IF NOT EXISTS vector_store_embedding_idx
    ON vector_store USING HNSW (embedding vector_cosine_ops);
```

#### Docker Compose: `docker/evidence/docker-compose.yml`
```yaml
services:
  postgres:
    # Official PostgreSQL 17 with pgvector pre-installed (drop-in replacement)
    image: pgvector/pgvector:pg17
    environment:
      POSTGRES_DB: diagnostician
      POSTGRES_USER: diag
      POSTGRES_PASSWORD: diag
```

### 9. Dependency & Wiring Points

* **Maven Dependency**: Add `spring-ai-vector-store` and `spring-ai-starter-model-pgvector-store` to `agent-core/pom.xml`.
* **Prompt Integrity**: The tool result is returned to the model via standard tool-calling envelopes (`ToolEnvelope`). **`SystemPrompts.SYSTEM` remains untouched**, keeping its pinned SHA-256 hash valid.
* **Failure Guard**: If `MemoryConfig.enabled() == false`, the tool returns `ToolEnvelope.ok("Episodic memory is disabled for this run.")` without executing any vector queries.

---

## Part 3 — Prompt Context Injection Format

When returned by `queryPastFindings` and formatted into the tool envelope:

```text
=== EPISODIC MEMORY (RELEVANT PAST EXPERIENCES) ===

[Experience 1] [PROVEN PATTERN - Other Service: practice-mvc-s4]
- SYMPTOM: Load: 210 RPS, p95 1850ms. Hotspots: JavaMonitorEnter (CRITICAL, count=45200, frames=[PetClinicService.findOwners])
- ACTION: Applied concurrency optimization on monitor.
- OUTCOME: KEPT (RPS +62%, p95 -45%)
- FINDING: Reducing synchronized block scope resolved thread parking cascade.
- ADVISORY: Succeeded on practice-mvc-s4 for similar lock symptoms. High-confidence candidate to investigate.

[Experience 2] [CAUTION - Same Service: petclinic]
- SYMPTOM: Load: 189 RPS, p95 2400ms. Hotspots: JavaMonitorEnter (CRITICAL, count=71738, frames=[JarFile.getBytes, URLClassPath.getLoader])
- ACTION: hikari-pool-size=30
- OUTCOME: REVERTED
- FINDING: Pool increase did not relieve lock contention on classloader.
- ADVISORY: Attempted and REVERTED on this service earlier. Strongly discouraged unless you have clear evidence that the underlying mechanism differs.

[Experience 3] [BASELINE - Same Service: petclinic]
- SYMPTOM: Load: 189 RPS, p95 2400ms. Hotspots: JavaMonitorEnter (CRITICAL, count=71738, frames=[JarFile.getBytes, URLClassPath.getLoader])
- ACTION: jar-unpack
- OUTCOME: KEPT
- FINDING: Unpacking jar eliminated 81% of lock contention events.
- ADVISORY: This fix was already KEPT in an earlier run and is active in your baseline. Verify before proposing again.

[Experience 4] [OBSERVATION - Other Service: petclinic-s2]
- SYMPTOM: Load: 205 RPS, p95 1900ms. Hotspots: JavaMonitorEnter (CRITICAL, count=51200, frames=[OrderService.checkout])
- ACTION: db-fetch-size=50
- OUTCOME: REVERTED
- FINDING: Changing JDBC fetch size caused memory pressure without reducing lock wait.
- ADVISORY: Reverted on petclinic-s2. Informative context: verify if your bottleneck shares the same root cause before attempting.
```

---

## Part 4 — TIGERSTYLE Milestones & Verification Gates

### Milestone M0: SymptomSummarizer & Document Factory Verification
- **Test**: `SymptomSummarizerTest` & `IterationDocumentFactoryTest`
- **Verifications**:
  1. `SymptomSummarizer` generates deterministic string strictly $\le 400$ characters. Correctly flags `CRITICAL` signals and includes top 2 frame signatures.
  2. Generates single atomic `Document` per iteration.
  3. Text contains exact uppercase headings (`SYMPTOM:`, `PROPOSAL:`, `OUTCOME:`, `FINDING:`).
  4. Metadata map contains `runId`, `targetId`, `outcome`, `category`, `iterationN`, `embeddingModel`, `schemaVersion`.
  5. Ensure zero null metadata entries (use `"NONE"` defaults).

### Milestone M1: Vector Ingestion & Basic Retrieval
- **Test**: `DiagnosticMemoryRetrieverTest`
- **Seam**: Runs 100% offline using Spring AI's in-memory `SimpleVectorStore`:
  ```java
  VectorStore vectorStore = SimpleVectorStore.builder(new FakeEmbeddingModel()).build();
  ```
- **Verifications**:
  1. Ingest batch of synthetic iterations (mix of S1, S2, S3 cases).
  2. Query with JFR lock symptom $\to$ top result is H5 lock iteration.
  3. Query with GC starvation symptom $\to$ top result is H3 GC iteration.
  4. Assert `embeddingModel` filter excludes vectors indexed under an older/mismatched model name.

### Milestone M2: Dual-Mode Filter & Diversity Verification
- **Test**: `MemoryFilterAndDiversityTest`
- **Verifications**:
  1. **Eval Mode**: Current target is `petclinic-s1`. Query returns S2/S3/S4 results only. Assert 0 results have `targetId == "petclinic-s1"`.
  2. **Production Mode**: Current target is `petclinic-s1`. Query returns `petclinic-s1` results alongside others.
  3. **Diversity Test**: Insert 3 iterations all with `(targetId="petclinic-s1", template="hikari-pool-size")`. Assert retriever deduplicates and returns at most 1 entry for this pair.

### Milestone M3: Tool & Prompt Context Formatting Verification
- **Test**: `MemoryContextFormatterTest` & `DiagnosticMemoryToolTest`
- **Verifications**:
  1. Same-target + `REVERTED` outputs `[CAUTION - Same Service]: ...` (discourages repetition with rationale, no blunt censorship).
  2. Cross-target + `REVERTED` outputs `[OBSERVATION - Other Service]: ...` (contextual warning, root causes may differ).
  3. Same-target + `KEPT` outputs `[BASELINE - Same Service]: ...` (prompts verification of current active baseline).
  4. Cross-target + `KEPT` outputs `[PROVEN PATTERN - Other Service]: ...` (proven recipe under similar symptoms).
  5. **Hybrid Search Test**: Query with `symptomQuery="lock contention", outcome="KEPT"` returns only KEPT lock records.
  6. **Guard 4 (Dynamic Threshold Verification)**: Query with `symptomQuery=null, category="H3", outcome="KEPT"`. Assert matching documents are returned even without a symptom query vector, verifying `similarityThreshold=0.0` prevents cosine distance dropouts across both `SimpleVectorStore` and `PgVectorStore`.
  7. **Guard 1 (Eval-Isolation)**: Verify `currentTargetId` cannot be overridden via query parameters and is enforced under evaluation mode.
  8. **Guard 2 (Whitelist Validation)**: Invoking tool with `category="database"` returns `ToolEnvelope.fail(...)` containing invalid category error. Invoking with `outcome="FAILED"` returns `ToolEnvelope.fail(...)`.
  9. **Guard 3 (All-Null Prevention)**: Invoking tool with all arguments null returns `ToolEnvelope.fail(...)` with descriptive error.
  10. **Guard 5 (Fail-Fast Length Caps & Sanitization)**: Query with `symptomQuery` length > 400 returns `ToolEnvelope.fail(...)` specifying character count and limit. Leading/trailing whitespace safely trimmed.

### Milestone M4: Deterministic Loop Verification Gate (`Step13VerifyGate`)
Adheres to the project's TigerStyle gate pattern (mirrors `Step9VerifyGate`): **100% offline, deterministic, zero API token cost**.

- **Gate**: `Step13VerifyGate.java`
- **Verifications**:
  1. **Ingestion Trigger Test**: Run 1 iteration of `AgentLoop`. Assert `IterationIndexer.index(...)` is called immediately after `KeepRule` evaluates `KEPT` or `REVERTED`, writing exactly 1 document to the store. Assert `WASTED` iterations are skipped.
  2. **Tool Execution & Safety Test**: Invoke `queryPastFindings` through `BoundedReadSource`. Assert output envelope contains verified advisory string and respect for `isEvaluationMode`.
  3. **Fail-Open Resilience Test**: Inject an artificial `VectorStoreException` / embedding timeout into `VectorStore`. Assert `queryPastFindings` returns `ToolEnvelope.fail(...)` and `AgentLoop` completes its decide turn gracefully without throwing exceptions.

> **Optional Real-Run Gate (`Step13RealRunGate`)**: Tagged with `@Tag("real-run")` (requires live API key). Asserts that an actual frontier LLM reasons over the advisory tags and generalizes from cross-target memory. Kept separate from the deterministic build gate so flaky model days never break CI.

---

## Part 5 — Failure Modes & Defenses

| Failure Mode | Trigger | Defense |
| :--- | :--- | :--- |
| **Embedding Outage / Timeout** | Remote embedding provider unavailable or slow | **Fail Open**: Catch exception, log warning at `WARN` level, return empty memory list. Agent loop proceeds with zero memory rather than crashing. |
| **Empty Vector Store** | First run on a fresh database | Return empty list immediately; prompt excludes memory section cleanly. |
| **Cross-Model Contamination** | Embedding model swap in Stage 2 (e.g. OpenAI to Ollama) | Metadata filter: `b.eq("embeddingModel", currentModel)`. Stale vectors are ignored until re-indexed. |
| **Redundant Guard Flooding** | Multiple iterations tested and failed the same template | Deduplicate candidates by `(targetId, template)` before taking top-K. |
| **Prompt Bloat / Distraction** | Low-relevance memories diluting context | Configurable `similarityThreshold` (default `0.70`). Discard anything below threshold. |
| **Eval Contamination** | Developer forgets to set `evaluationMode = true` during benchmarks | Automated assertion in `MatrixRunner` / `Step11EvalHarness` enforcing evaluation mode filter. |

---

## Part 6 — Architecture Rationale & Tradeoffs (LinkedIn / Tech-Showcase Reference)

This section documents the foundational engineering decisions and tradeoffs behind Step 13. Designed as a reference architecture for technical deep-dives and LinkedIn articles.

```
┌────────────────────────────────────────────────────────────────────────────────────────┐
│                        7 CORE ARCHITECTURAL DECISIONS AT A GLANCE                      │
├────────────────────────────┬─────────────────────────────┬─────────────────────────────┤
│ Dimension                  │ Standard "Tutorial" Pattern │ Our Production Architecture │
├────────────────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 1. Chunking                │ TokenTextSplitter (chunks)  │ Atomic Document (0 splits)  │
│ 2. Retrieval Trigger       │ Passive Advisor on prompt   │ Agentic @Tool on-demand     │
│ 3. Storage Abstraction     │ DocumentRetriever wrapper   │ Direct VectorStore API      │
│ 4. Evaluation Safety       │ Blind vector search         │ Dual-mode metadata filter   │
│ 5. LLM Client Topology     │ Single shared ChatClient    │ 2 Decoupled ChatClients     │
│ 6. Vector Persistence      │ Separate Vector DB cluster  │ In-database pgvector (HNSW) │
│ 7. Query Surface           │ 2 Split Tools (Text/Meta)   │ 1 Unified Tool + 5 Guards   │
└────────────────────────────┴─────────────────────────────┴─────────────────────────────┘
```

### Tradeoff 1: Atomic Episodes vs. TokenTextSplitter (Why We Skipped Chunking)
* **The Tutorial Approach**: Always pass incoming documents through `TokenTextSplitter` (e.g. 500 tokens, 50-token overlap).
* **Why It Fails for Agents**: A diagnostic iteration is a compact, self-contained **episodic unit** (~150–350 tokens). If chunked, the causal chain is destroyed: Chunk 1 gets the `SYMPTOM`, while Chunk 2 gets the `PROPOSAL` and `OUTCOME`. 
* **The Production Choice**: **1 Iteration = 1 Atomic Document**. In Case-Based Reasoning (CBR), an episode is indivisible. Zero text chunking preserves the exact relationship between symptom, action, outcome, and post-hoc finding.

### Tradeoff 2: Agentic Tool-Based RAG (`@Tool`) vs. Passive Prompt Stuffing (`RetrievalAugmentationAdvisor`)
* **The Tutorial Approach**: Attach `RetrievalAugmentationAdvisor` to `ChatClient` so every prompt automatically intercepts and injects retrieved context.
* **Why It Fails for Agents**: 
  1. *Prompt Bloat*: Injects 300–600 tokens of past history into every turn, burning cost even when the agent is making an obvious code edit.
  2. *Query Noise*: By default, advisors embed the entire user prompt. In an agent loop, the prompt is a 2,000-character JSON context (`DecideContext`), producing a noisy, diluted vector that ruins similarity matching.
* **The Production Choice**: **On-Demand Tool (`@Tool queryPastFindings`)**. The LLM decides *when* to search memory and *what* focused query to formulate (e.g. `queryPastFindings("lock contention JarFile ClassLoader")`). Memory is pulled only when needed.

### Tradeoff 3: Direct `VectorStore` Primitive vs. `DocumentRetriever` Wrapper (TigerStyle)
* **The Tutorial Approach**: Wrap the store inside `VectorStoreDocumentRetriever` to adhere to high-level RAG interfaces.
* **Why It Fails for Agents**: `DocumentRetriever` is **read-only** (`retrieve(Query)`). An agentic system has a dual lifecycle: it must *write* completed iterations (`add`) and *read* prior experiences (`similaritySearch`). Using `DocumentRetriever` forces you to inject both interfaces anyway.
* **The Production Choice**: **Operate directly over `VectorStore`**. Following `TIGERSTYLE.md` (*"Simplicity over cleverness, no magic pipelines"*), we use `vectorStore.similaritySearch(SearchRequest)` directly. It provides fluent dynamic filtering (`FilterExpressionBuilder`), eliminates wrapper ceremony, and simplifies unit tests to 2-line mocks.

### Tradeoff 4: Dual-Mode Isolation & Non-Dogmatic Advisories (The Over-Constrained Agent Trap)
* **The Problem**: If an agent is benchmarked on Target `S1` and retrieves past runs from Target `S1`, it simply looks up the answer key. This is **train/test contamination (data leakage)**—the benchmark becomes an illusion.
* **The Dogmatic Trap**: Saying `[GUARD]: DO NOT RE-ATTEMPT` turns memory into a blunt censor. A change that was reverted on Target A because the root cause was elsewhere (e.g. tuning DB pool during classloader contention) might be the *exact* solution needed on Target B!
* **The Production Choice**: **Dual-Mode Metadata Filtering with 2x2 Evidence Advisories**:
  * *Mode 1 (Benchmark / Eval)*: Enforces `b.ne("targetId", currentTargetId)`. Forces the agent to prove cross-target generalization.
  * *Mode 2 (Production Continuous)*: Allows same-target matches, but replaces rigid bans with contextual Bayesian evidence:
    * Same Target + `REVERTED` $\to$ `[CAUTION - Same Service]` (discourages repetition with rationale, avoids blunt censorship).
    * Cross Target + `REVERTED` $\to$ `[OBSERVATION - Other Service]` (contextual warning, root causes may differ).
    * Same Target + `KEPT` $\to$ `[BASELINE - Same Service]` (already in baseline, check before re-proposing).
    * Cross Target + `KEPT` $\to$ `[PROVEN PATTERN - Other Service]` (high-confidence recipe under similar symptoms).

### Tradeoff 5: 2 `ChatClient` Decoupling (The Diagnostician vs. The Vector Subsystem)
* **The Tutorial Approach**: Route all completions and embeddings through one monolithic client configuration.
* **Why It Fails for Agents**: Tool-calling models (Anthropic Claude 3.5 Sonnet / GPT-4o) have different pricing, rate limits, and latency profiles than embedding models. Tightly coupling them creates configuration spaghetti.
* **The Production Choice**: **Clean 2-Client Separation**:
  * `ChatClient 1`: The Diagnostician. Fast, tool-equipped, drives the loop.
  * `ChatClient 2` / Embedding Pipeline: Handles vectorization and similarity search against Postgres. Swapping embedding models (e.g. OpenAI to local Ollama in Stage 2) touches zero agent-loop code.

### Tradeoff 6: Native PostgreSQL (`pgvector`) vs. Dedicated Vector Database
* **The Tutorial Approach**: Spin up a separate Pinecone, Milvus, or Qdrant cluster for vectors.
* **Why It Fails for Agents**: Operational complexity. Our agent already relies on PostgreSQL for the Evidence DB (`run`, `iteration`, `load_report`, `jfr_report`). Adding a separate vector database creates dual-write consistency problems and extra Docker overhead.
* **The Production Choice**: **In-Database `pgvector` with HNSW Index**. By switching the Docker image to `pgvector/pgvector:pg17` and running a 10-line Flyway migration (`V3__vector_store.sql`), relational runs and vector embeddings live in the same bounded container, transactionally consistent and backup-ready.

### Tradeoff 7: 1 Unified Hybrid Tool vs. 2 Separate Tools (Semantic + Metadata)
* **The Tutorial Approach**: Create two distinct tools: `queryPastSymptoms(symptomText)` for semantic search, and `queryPastRunsByMetadata(category, template, outcome)` for SQL metadata filtering.
* **Why It Fails for Agents**:
  1. *Tool Choice Paralysis*: When faced with two overlapping tools, LLMs frequently get confused about which to call first. Looking for past GC fixes? The agent often calls both tools across 2 separate loop turns, burning double token budget and latency.
  2. *Hybrid Blindness*: An agent cannot execute a compound query like *"Find past lock contention symptoms where the outcome was actually KEPT"*. The semantic tool lacks outcome filtering, and the metadata tool cannot match symptom semantics.
* **The Production Choice**: **1 Unified Tool (`queryPastFindings`) with 5 Strict Metadata Guards**.
  The LLM has one clear, intuitive tool that accepts any combination of semantic text and structured filters. Server-side validation handles enum whitelisting, eval-mode target isolation, unbounded scan prevention, and dynamic thresholding, eliminating hallucination risks while giving the model maximum diagnostic flexibility.

