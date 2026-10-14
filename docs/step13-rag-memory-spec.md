# Step 13 Specification — RAG Episodic Memory Capstone

Companion to `v1.0-scope.md` (§4.4, §6), `schema.md`, `ai-ml-study-path.md` (Horizon 1 Capstone), and `TIGERSTYLE.md`.
Follows explicit failure modes, atomic documents, no silent swallows, and deterministically testable milestones (M0–M4).

---

## Part 0 — Goal & Mental Model

### Goal
Implement episodic vector memory for the autonomous diagnostic agent. Each historical iteration is vectorized as an atomic document containing the diagnostic symptom, proposal, outcome, and post-hoc finding, paired with rich metadata. The system supports two operational modes:
1. **Eval / Benchmark Mode**: Enforces cross-target retrieval (`targetId != currentTargetId`) to prevent test-set answer leakage and scientifically measure general diagnostic transfer.
2. **Production Mode**: Allows same-target retrieval (`targetId == currentTargetId`) to provide institutional repository memory, acting as guardrails so the agent never repeats past reverted mistakes or redundant merged fixes.

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
 │   - queryPastFindings(symptomQuery) ────────────┐                      │
 └─────────────────────────────────────────────────┼──────────────────────┘
                                                   │ LLM executes tool
                                                   ▼
 ┌────────────────────────────────────────────────────────────────────────┐
 │          DiagnosticMemoryTool / DiagnosticMemoryRetriever              │
 │                                                                        │
 │  Mode 1 (Benchmark): targetId != currentTargetId (Zero Leakage)        │
 │  Mode 2 (Production): All targets allowed, with:                       │
 │                       - [GUARD]: Attempted & REVERTED (Do not repeat!) │
 │                       - [CONTEXT]: Attempted & KEPT (Already merged!)  │
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
- **Semantics**:
  - `targetId == currentTargetId && outcome == KEPT`: The prompt explicitly warns the LLM that this fix is **already merged into the baseline**, instructing it to search for the next constraint.
  - `targetId == currentTargetId && outcome == REVERTED`: The prompt explicitly warns the LLM that this proposal was **previously attempted and failed**, instructing it not to waste compute repeating it.

### D5: Deduplication & Diversity Constraint
Nearest-neighbor vector search can return multiple iterations from the same target testing the same proposal.
* **Diversity Rule**: The retriever deduplicates retrieved candidates by `(targetId, template)`. If multiple records share the same target and template, only the highest-scoring candidate is retained.
* This ensures the top-K budget (default 3) delivers diverse perspectives rather than 3 identical warnings.

### D6: Agentic Tool-Based Retrieval (On-Demand vs. Passive Stuffing)
Rather than blindly stuffing past iterations into the prompt context on every single turn (passive RAG):
* **On-Demand Search**: The LLM is provided with a dedicated tool (`queryPastFindings(symptomQuery)`).
* **Targeted Inquiries**: The LLM formulates specific queries when it identifies suspect bottlenecks (e.g. `queryPastFindings("lock contention JarFile ClassLoader")` or `queryPastFindings("hikari connection pool exhaustion")`).
* **Invisible Server-Side Guardrails**: The tool intercepts the LLM's query, enforces `isEvaluationMode` filtering, dedups results, and decorates matches with `[GUARD]` or `[CONTEXT]` before returning the envelope.

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
     * Retrieves diverse past diagnostic experiences matching current symptoms.
     * 
     * Operational parameters (topK, similarityThreshold, evaluationMode, embeddingModel)
     * are read directly from the injected MemoryConfig and executed directly via
     * VectorStore.similaritySearch(SearchRequest).
     *
     * @param symptomQuery Formatted symptom summary or LLM-formulated query
     * @param currentTargetId The target currently being diagnosed
     * @return List of deduplicated, filtered memory documents (size <= config.topK())
     */
    List<Document> retrieve(String symptomQuery, String currentTargetId);
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
     * Formats retrieved documents into the system prompt section, decorating
     * same-target memories with operational guardrails (already merged / failed).
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
import org.springframework.ai.tool.annotation.Tool;

@Tool(description = """
        Search episodic memory of past diagnostic runs across services for similar symptoms, bottlenecks, or fixes.
        Provide a focused query describing the symptom or suspect mechanism (e.g. 'lock contention on classloader'
        or 'Hikari connection pool timeout under load').
        Returns past attempted actions, whether they were KEPT or REVERTED, and key findings.
        """)
public ToolEnvelope<String> queryPastFindings(String symptomQuery) {
    try {
        if (symptomQuery == null || symptomQuery.isBlank()) {
            return ToolEnvelope.fail("symptomQuery must not be blank");
        }

        // 1. Retrieve with server-side safety guards (eval isolation, dedup, threshold)
        List<Document> memories = memoryRetriever.retrieve(symptomQuery, currentTargetId);
        if (memories.isEmpty()) {
            return ToolEnvelope.ok("No matching past diagnostic runs found for: " + symptomQuery);
        }

        // 2. Format with [GUARD] / [CONTEXT] tags
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

When injected into the `DecideTurn` prompt:

```text
=== EPISODIC MEMORY (RELEVANT PAST EXPERIENCES) ===

[Experience 1] (Cross-Target: practice-mvc-s4)
- SYMPTOM: Load: 210 RPS, p95 1850ms. Hotspots: JavaMonitorEnter (CRITICAL, count=45200, frames=[PetClinicService.findOwners])
- ACTION: Applied concurrency optimization on monitor.
- OUTCOME: KEPT (RPS +62%, p95 -45%)
- FINDING: Reducing synchronized block scope resolved thread parking cascade.

[Experience 2] (SAME REPOSITORY: petclinic)
- SYMPTOM: Load: 189 RPS, p95 2400ms. Hotspots: JavaMonitorEnter (CRITICAL, count=71738, frames=[JarFile.getBytes, URLClassPath.getLoader])
- ACTION: hikari-pool-size=30
- OUTCOME: REVERTED
- FINDING: Pool increase did not relieve lock contention on classloader.
[GUARD]: This exact change was attempted and REVERTED on this target before. DO NOT RE-ATTEMPT.

[Experience 3] (SAME REPOSITORY: petclinic)
- SYMPTOM: Load: 189 RPS, p95 2400ms. Hotspots: JavaMonitorEnter (CRITICAL, count=71738, frames=[JarFile.getBytes, URLClassPath.getLoader])
- ACTION: jar-unpack
- OUTCOME: KEPT
- FINDING: Unpacking jar eliminated 81% of lock contention events.
[CONTEXT]: This fix was already KEPT and is part of your current baseline. Search for remaining bottlenecks.
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
  1. Same-target + `REVERTED` outputs `[GUARD]: ... DO NOT RE-ATTEMPT`.
  2. Same-target + `KEPT` outputs `[CONTEXT]: ... already part of your current baseline`.
  3. Cross-target memories output standard diagnostic context without guard/context tags.
  4. Tool `queryPastFindings("lock contention")` successfully returns `ToolEnvelope.ok(...)` with formatted memory, or `ToolEnvelope.ok("No matching...")` when empty.

### Milestone M4: Deterministic Loop Verification Gate (`Step13VerifyGate`)
Adheres to the project's TigerStyle gate pattern (mirrors `Step9VerifyGate`): **100% offline, deterministic, zero API token cost**.

- **Gate**: `Step13VerifyGate.java`
- **Verifications**:
  1. **Ingestion Trigger Test**: Run 1 iteration of `AgentLoop`. Assert `IterationIndexer.index(...)` is called immediately after `KeepRule` evaluates `KEPT` or `REVERTED`, writing exactly 1 document to the store. Assert `WASTED` iterations are skipped.
  2. **Tool Execution & Safety Test**: Invoke `queryPastFindings` through `BoundedReadSource`. Assert output envelope contains verified `[GUARD]` string and respect for `isEvaluationMode`.
  3. **Fail-Open Resilience Test**: Inject an artificial `VectorStoreException` / embedding timeout into `VectorStore`. Assert `queryPastFindings` returns `ToolEnvelope.fail(...)` and `AgentLoop` completes its decide turn gracefully without throwing exceptions.

> **Optional Real-Run Gate (`Step13RealRunGate`)**: Tagged with `@Tag("real-run")` (requires live API key). Asserts that an actual frontier LLM avoids a guarded template and generalizes from cross-target memory. Kept separate from the deterministic build gate so flaky model days never break CI.

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
│                        6 CORE ARCHITECTURAL DECISIONS AT A GLANCE                      │
├────────────────────────────┬─────────────────────────────┬─────────────────────────────┤
│ Dimension                  │ Standard "Tutorial" Pattern │ Our Production Architecture │
├────────────────────────────┼─────────────────────────────┼─────────────────────────────┤
│ 1. Chunking                │ TokenTextSplitter (chunks)  │ Atomic Document (0 splits)  │
│ 2. Retrieval Trigger       │ Passive Advisor on prompt   │ Agentic @Tool on-demand     │
│ 3. Storage Abstraction     │ DocumentRetriever wrapper   │ Direct VectorStore API      │
│ 4. Evaluation Safety       │ Blind vector search         │ Dual-mode metadata filter   │
│ 5. LLM Client Topology     │ Single shared ChatClient    │ 2 Decoupled ChatClients     │
│ 6. Vector Persistence      │ Separate Vector DB cluster  │ In-database pgvector (HNSW) │
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

### Tradeoff 4: Dual-Mode Isolation (Benchmark Data Leakage vs. Production Repository Memory)
* **The Problem**: If an agent is benchmarked on Target `S1` and retrieves past runs from Target `S1`, it simply looks up the answer key. This is **train/test contamination (data leakage)**—the benchmark becomes an illusion.
* **The Catch**: In a real production system, retrieving past runs on the *same repository* from 3 months ago is **not leakage—it is valuable institutional memory!**
* **The Production Choice**: **Dual-Mode Metadata Filtering**:
  * *Mode 1 (Benchmark / Eval)*: Enforces `b.ne("targetId", currentTargetId)`. Forces the agent to prove cross-target generalization.
  * *Mode 2 (Production Continuous)*: Allows same-target matches, but decorates them as actionable behavioral guardrails:
    * `outcome == REVERTED` $\to$ `[GUARD]: Attempted and REVERTED before. DO NOT RE-ATTEMPT.`
    * `outcome == KEPT` $\to$ `[CONTEXT]: Already KEPT in past run. Part of your current baseline.`

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
