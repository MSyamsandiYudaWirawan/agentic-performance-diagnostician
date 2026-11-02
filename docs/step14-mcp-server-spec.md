# Step 14 Specification — Model Context Protocol (MCP) Diagnostic Knowledge Server

Companion to `v1.0-scope.md`, `step13-rag-memory-spec.md`, and `TIGERSTYLE.md`.
Follows TigerStyle engineering standards: explicit preconditions, strict input validation, deterministic test gates (M0–M4), and no hidden state.

---

## Part 0 — Goal & Mental Model

### Goal
Expose the system's centralized **Episodic Diagnostic Memory** (`pgvector` hybrid vector search) as a standardized **Model Context Protocol (MCP)** server over **Streamable HTTP** (the current MCP transport standard; the legacy HTTP/SSE transport is deprecated in the 2025 protocol revisions).

This allows external AI clients—including developer IDEs (**Cursor, Claude Desktop, Antigravity**), CI triage bots, and secondary agents—to dynamically discover and query historical JVM performance diagnostics (`query_past_findings`) without requiring direct PostgreSQL JDBC database access or credentials.

```
 ┌────────────────────────┐      ┌─────────────────────────┐      ┌───────────────────────┐
 │   Claude Desktop /     │      │   Autonomous CI Agent   │      │   Developer IDE /     │
 │   Incident Slack Bot   │      │   (Order Service CI)    │      │   Cursor / Antigravity│
 └───────────┬────────────┘      └────────────┬────────────┘      └───────────┬───────────┘
             │                                │                               │
             │   MCP over Streamable HTTP                                    │
             ▼                                ▼                               ▼
┌───────────────────────────────────────────────────────────────────────────────────────────┐
│                     MCP Diagnostic Knowledge Server (Spring AI 2.0.1)                     │
│                                                                                           │
│  Endpoint: POST /api/v1/mcp (JSON-RPC 2.0 — Streamable HTTP, Mcp-Session-Id header)        │
│                                                                                           │
│  [Tool: query_past_findings]                         [Resource: diagnostics://hypotheses] │
│  • Symptom vector search (HNSW cosine >= 0.65)       • H1–H7 diagnostic category taxonomy │
│  • Server-side AST filters (category, template)                                           │
│  • 3x over-fetch diversity deduplication             [Resource: diagnostics://cases/{id}] │
│  • 2x2 Advisory Matrix formatting                   • Historical run & JFR metric report  │
│                                                                                           │
│  [Tool: run_diagnostic_benchmark (Roadmap)]                                               │
│  • Inputs: { repoUrl, commitSha, loadProfile }                                            │
│  • Pure Observability: Runs isolated k6 load test + captures binary JFR signals           │
│  • Zero Side-Effects: Returns LoadReportDto + JfrReportDto without writing to pgvector    │
│                                                                                           │
│  [Tool: verify_and_index_optimization (Roadmap)]                                          │
│  • Inputs: { repoUrl, baseCommitSha, gitPatch, loadProfile }                              │
│  • Sandboxed Execution: Clones repo, runs baseline, applies patch, builds & runs k6 + JFR │
│  • Objective Keep-Rule v2: Ground-truth math prevents caller metric falsification         │
│  • Automated Ingestion: Automatically indexes KEPT/REVERTED outcomes in pgvector (UUIDv3) │
│  • Output: Returns verified LoadReportDto, JFR diff, and empirical verdict to caller      │
└─────────────────────────────────────────────┬─────────────────────────────────────────────┘
                                              │
                                              ▼
                             PostgreSQL 17 + pgvector HNSW
```

### Why MCP over Streamable HTTP?
1. **Stateless Knowledge Federation**: External developers can query institutional diagnostic knowledge from their IDE while investigating production JVM issues.
2. **Security & Boundary Separation**: Keeps local execution tools (`applyChange`, `runBenchmark` via Docker) isolated locally, while publishing read-only diagnostic memory safely over network boundaries.
3. **Database Decoupling**: Clients interact through standard JSON-RPC 2.0 MCP schemas; the MCP server encapsulates connection pooling, vector indexing, and SQL AST compilation.
4. **Current Transport Standard**: Streamable HTTP replaced HTTP/SSE in the 2025 MCP protocol revisions — one endpoint, optional SSE-framed responses, session identity via the `Mcp-Session-Id` header.

---

## Part 1 — Architectural Design & Module Structure

### Module Placement: New Module `mcp-server` (Standalone Process)
`mcp-server` runs as an independent Spring Boot process (port 8085) sharing the same PostgreSQL 17 instance as `agent-core`. It is **not** embedded inside `agent-core`.

**Why `mcp-server` depends on `agent-core` (not just `evidence`):**
- `query_past_findings` → `DiagnosticMemoryRetriever` + `MemoryContextFormatter` live in `agent-core`
- `run_diagnostic_benchmark` (roadmap) → `TargetPipeline` lives in `target-runner` (transitive via `agent-core`)
- `verify_and_index_optimization` (roadmap) → `AgentLoop` lives in `agent-core`

**Module dependency graph:**
```
evidence
    ↑
target-runner
    ↑
agent-core  (DiagnosticMemoryRetriever, AgentLoop)
    ↑
mcp-server  (McpServerApplication — tools + resources, standalone Spring Boot app)
```

**Actual module contents (as implemented):**
- `io.diag.mcp.McpServerApplication` — boot entry point. Scan = `io.diag.mcp` + `io.diag.evidence` (NOT all of `io.diag`: `DiagnosticTools` is run-scoped and must not enter a long-lived context); explicit `@EnableJdbcRepositories("io.diag.evidence.repository")` (repo discovery follows the app-class package); `@Import` of `DiagnosticMemoryRetrieverImpl` + `MemoryContextFormatterImpl`; `@EnableConfigurationProperties(MemoryConfig.class)`.
- `io.diag.mcp.DiagnosticMemoryMcpService` — `query_past_findings` tool (lite-mode `@Bean` `SyncToolSpecification` list).
- `io.diag.mcp.DiagnosticMcpResourceProvider` — both resources (lite-mode `@Bean` `SyncResourceSpecification` list).
- `application.yml` — transport + datasource. The agent-core jar's own `application.properties` supplies `diag.memory.*`, embedding endpoint/key, and pgvector HNSW/COSINE config (keys disjoint from the yml by design).

### Maven Dependencies (`mcp-server/pom.xml`, as implemented)
```xml
<dependencies>
    <!-- Pulls in evidence + target-runner transitively, plus the memory stack config -->
    <dependency>
        <groupId>io.diag</groupId>
        <artifactId>agent-core</artifactId>
    </dependency>

    <!-- Spring AI MCP Server Starter (Spring Boot 4 / Spring AI 2.0.x) -->
    <dependency>
        <groupId>org.springframework.ai</groupId>
        <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
    </dependency>

    <!-- WebMVC (Tomcat) hosting the Streamable HTTP transport -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>

    <!-- spring-ai-vector-store must NOT be declared here (not even test-scope):
         a direct test declaration wins Maven's nearest-wins resolution and downgrades
         agent-core's transitive compile scope, evicting VectorStore from the boot jar. -->

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

---

## Part 2 — MCP Tool Specification: `query_past_findings`

### Tool Definition (`tools/list`)
* **Name**: `query_past_findings`
* **Description**:
  > Search episodic memory of past diagnostic runs across JVM services for similar symptoms, bottlenecks, or fixes. Supports semantic search, structured metadata filtering, or hybrid search. Returns past attempted actions, whether they were KEPT or REVERTED, and key findings.

### Input JSON Schema
```json
{
  "type": "object",
  "properties": {
    "symptomQuery": {
      "type": "string",
      "description": "Suspect symptom, bottleneck, or mechanism (e.g. 'lock contention on classloader' or 'Hikari connection pool timeout'). Max 400 chars."
    },
    "category": {
      "type": "string",
      "description": "Diagnostic hypothesis category: H1 (CPU), H2 (IO/Pool), H3 (GC/Alloc), H4 (Lock/Sync), H5 (Classload), H6 (Config), H7 (Custom)."
    },
    "template": {
      "type": "string",
      "description": "Action template name (e.g. 'hikari-pool-size', 'jar-unpack', 'jvm-opts'). Max 50 chars."
    },
    "outcome": {
      "type": "string",
      "enum": ["KEPT", "REVERTED"],
      "description": "Outcome filter: 'KEPT' (only successful fixes) or 'REVERTED' (failed attempts)."
    },
    "targetId": {
      "type": "string",
      "description": "Optional target service identifier. When querying from an IDE for a specific service, allows advisory provenance ranking."
    }
  },
  "required": []
}
```

### Execution Behavior (`tools/call`)
1. **Server-Side Validation**:
   - Rejects empty calls: at least one **search** parameter (`symptomQuery`, `category`, `template`, `outcome`) must be non-blank. `targetId` is provenance context only — a `targetId`-only call is rejected (it would otherwise run an unfiltered top-K dump).
   - Enforces length caps: `symptomQuery <= 400`, `template <= 50`.
   - Validates regex enum: `category` matches `^H[1-7]$`.
   - Defense in depth: the MCP SDK 2.0.0 `ToolInputValidator` additionally enforces the declared JSON Schema (e.g. the `outcome` enum) before the handler runs.
2. **Vector Retrieval**:
   - Delegates to `DiagnosticMemoryRetriever.retrieve(query, targetId)`.
   - Executes HNSW cosine search ($\ge 0.65$) combined with SQL AST metadata filters.
   - Executes 3× over-fetch deduplication by `(targetId : template)`.
3. **Response Formatting**:
   - Formats results using `MemoryContextFormatter` into the 2×2 Advisory Matrix (`[PROVEN PATTERN]`, `[CAUTION]`, `[BASELINE]`, `[OBSERVATION]`).
   - Returns MCP `CallToolResult` wrapping `TextContent`.

---

## Part 3 — MCP Resources (Full Protocol Depth)

The server exposes **Resources** — the read-only knowledge surface that pairs naturally with `query_past_findings`:

### Resources (`resources/list` & `resources/read`)
* **`diagnostics://hypotheses/guide`**:
  - MIME: `text/markdown`
  - Returns the official H1–H7 diagnostic hypothesis taxonomy and typical JFR signal patterns (e.g., `JavaMonitorEnter` $\rightarrow$ H4, `ThreadPark` $\rightarrow$ H2).
* **`diagnostics://cases/{runId}`**:
  - MIME: `application/json`
  - Returns the complete forensic trail of a specific historical run: baseline metrics, all iterations, JFR diff summaries, and final outcome.

### Prompts — deliberately dropped
A `triage_jfr_incident` prompt (args: `serviceName`, `observedSymptom`) was originally specced here. **Decision 2026-11-02: skipped.** Rationale: server-side prompt templates invert the knowledge direction — the server holds the *data* (episodes, taxonomy, forensic trails) but knows nothing about how an LLM should reason over a live incident; the client (IDE assistant, CI bot) is where triage prompting belongs, composed around `query_past_findings` + the resources above. Tools + resources already demonstrate full protocol depth. Revisit only if a real consumer explicitly asks for server-side prompt templates.

---

## Part 4 — Transport & Network Configuration

### Streamable HTTP Transport
* **Protocol Standard**: Model Context Protocol JSON-RPC 2.0 over **Streamable HTTP** (protocol revision `2025-11-25` as negotiated by the SDK; the legacy HTTP/SSE transport pair is deprecated).
* **Endpoint**: a single `POST /api/v1/mcp`. Clients send JSON-RPC requests (`initialize`, `tools/list`, `tools/call`, `resources/read`) with `Accept: application/json, text/event-stream`; responses may arrive as plain JSON or SSE-framed streams. Session identity is the `Mcp-Session-Id` response header from `initialize`, echoed by the client on subsequent requests.
* **Default Port**: `8085` (standard `server.port`).

### Actual Configuration (`mcp-server/src/main/resources/application.yml`)
```yaml
server:
  port: 8085

spring:
  ai:
    mcp:
      server:
        name: diag-memory-mcp-server
        version: 1.0.0
        type: sync
        protocol: streamable
        streamable-http:
          mcp-endpoint: /api/v1/mcp
  datasource:
    url: ${DIAG_DB_URL:jdbc:postgresql://localhost:5432/diagnostician?stringtype=unspecified}
    username: ${DIAG_DB_USERNAME:diag}
    password: ${DIAG_DB_PASSWORD:diag}

evidence:
  artifacts-root: target/mcp-artifacts   # never persists; keeps cwd clean
```
The agent-core jar's `application.properties` merges in the rest: `diag.memory.*`, the decoupled embedding endpoint (`EMBEDDING_BASE_URL`/`EMBEDDING_API_KEY`, offline-safe defaults), and pgvector HNSW/COSINE + Flyway vector-dimension settings.

---

## Part 5 — Enterprise Full-Cycle Roadmap: Benchmark-as-a-Service & Observability

### 1. On-Demand Profiling Sandbox: `run_diagnostic_benchmark`
Before attempting or committing an optimization patch, engineers and secondary AI agents often require raw JFR and throughput telemetry on a target commit without mutating enterprise memory:
- **Zero Side-Effects**: Runs an isolated k6 load test and JFR capture in an isolated container. Does **not** ingest into `pgvector`.
- **Developer Freedom**: Allows developers to explore, profile, and test ad-hoc hypotheses without contributing noise or unfinished experiments to the shared knowledge base.

#### Concurrency Model (Virtual Threads + Semaphore)
Both `run_diagnostic_benchmark` and `verify_and_index_optimization` are **blocking and stateful** — each run spawns Docker containers, k6 processes, and JFR captures. The concurrency model uses:

1. **Virtual thread per MCP call** — each incoming tool call runs on a virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`), keeping the HTTP/SSE layer non-blocking while the benchmark blocks.
2. **Semaphore cap** — a `Semaphore(DIAG_MAX_CONCURRENT_BENCHMARKS)` (default `1`, configurable via env var) prevents host resource exhaustion. Callers that exceed the cap receive an immediate `BenchmarkCapacityException` (fail-fast, no queue).
3. **Timeout ceiling** — each benchmark call is bounded by `DIAG_BENCHMARK_TIMEOUT_MINUTES` (default `30 min`) matching `LoopConfig.wallClockLimitMinutes`.

```
MCP call arrives
       │
       ▼
 Virtual thread spawned
       │
       ▼
 Semaphore.tryAcquire() ──── fail ──→ BenchmarkCapacityException (503)
       │ acquired
       ▼
 AgentLoop / TargetPipeline runs (blocking, isolated Docker)
       │
       ▼
 Semaphore.release() + return result
```

This is intentionally fail-fast — no internal queue. CI bots and IDE callers are expected to retry with backoff.

#### Protocol Schema: `run_diagnostic_benchmark`
```json
{
  "type": "object",
  "properties": {
    "repoUrl": {
      "type": "string",
      "description": "Git repository URL of the target service."
    },
    "commitSha": {
      "type": "string",
      "description": "Git commit SHA to checkout and profile."
    },
    "loadProfile": {
      "type": "string",
      "description": "k6 load profile name (e.g. 'standard-50vu', 'soak-200vu')."
    }
  },
  "required": ["repoUrl", "commitSha"]
}
```

---

### 2. The Vector Store Poisoning Vulnerability & `verify_and_index_optimization`
Allowing external clients or untrusted agents direct write access (`index_iteration`) to episodic memory introduces a fatal vulnerability: **Vector Store Poisoning**. An uncalibrated or hallucinating model could insert fabricated latency improvements or broken fixes into `pgvector`, corrupting future retrieval queries for all other engineers and automated agents.

### The Authoritative Verification Solution: `verify_and_index_optimization`
Instead of accepting self-reported numbers from callers, the enterprise architecture exposes **Benchmark-as-a-Service**: the caller submits only code, and the diagnostic platform authoritatively measures the physical reality on its own hardware and sandboxes.

```
External Developer / Agent (IDE / CI PR Bot)
             │
             │ Calls MCP Tool: verify_and_index_optimization
             │ Inputs: { repoUrl, baseCommitSha, gitPatch, loadProfile }
             ▼
┌──────────────────────────────────────────────────────────────────────────┐
│                   Trusted Diagnostic Platform (Your MCP Server)          │
│                                                                          │
│ 1. Git Checkout: Clones repository at baseCommitSha in isolated runner   │
│ 2. 3x Baseline Run: Executes k6 load test -> calculates noise floor      │
│ 3. Patch Application: Applies gitPatch inside isolated Docker container  │
│ 4. Build & Smoke Gate: Ensures compilation and healthy endpoints         │
│ 5. Peak Benchmark & JFR: Executes load profile and captures raw events   │
│ 6. Mathematical Keep-Rule v2: Evaluates throughput, p50, and p95 deltas  │
│                                                                          │
│ [Automatic Internal Ingestion]:                                          │
│ Only if verification passes -> platform indexes empirical telemetry      │
│ directly into PostgreSQL 17 + pgvector (UUIDv3 idempotent upsert)        │
└────────────────────────────────────┬─────────────────────────────────────┘
                                     │
                                     ▼
        Returns verified verdict, LoadReportDto & JFR diff to caller
```

### Protocol Schema: `verify_and_index_optimization`
```json
{
  "type": "object",
  "properties": {
    "repoUrl": {
      "type": "string",
      "description": "Git repository URL (e.g. 'git@github.com:myorg/order-service.git')."
    },
    "baseCommitSha": {
      "type": "string",
      "description": "Baseline git commit SHA prior to the proposed optimization."
    },
    "gitPatch": {
      "type": "string",
      "description": "Standard git diff/patch representing the proposed code/config change."
    },
    "loadProfile": {
      "type": "string",
      "description": "Target k6 benchmark load profile (e.g. 'standard-50vu', 'soak-200vu')."
    }
  },
  "required": ["repoUrl", "baseCommitSha", "gitPatch"]
}
```

### Core Enterprise Guarantees:
1. **100% Tamper-Proof Telemetry**: Callers cannot lie about numbers because they never provide metrics—the platform runs the benchmarks.
2. **Ground-Truth RAG Quality**: Every episode ingested into PostgreSQL 17 + `pgvector` has an immutable forensic record backed by raw JFR binaries and k6 logs.
3. **Seamless CI/CD Integration**: A GitHub Actions / GitLab CI bot can call this MCP tool on every pull request that touches JVM tuning flags or thread pool configurations.

---

## Part 6 — Implementation Milestones (M0 – M4)

### Milestone M0: Dependency & Configuration Foundation — ✅ DONE
- `mcp-server/pom.xml` inherits from `diagnostician-parent`, depends on `agent-core` + `spring-ai-starter-mcp-server-webmvc` (+ `spring-ai-vector-store` test scope for the gate).
- `mcp-server` registered in root `pom.xml`.
- `application.yml` declares server metadata (`diag-memory-mcp-server`), Streamable HTTP transport, port 8085, datasource. (`DIAG_MAX_CONCURRENT_BENCHMARKS` / `DIAG_BENCHMARK_TIMEOUT_MINUTES` env bindings are roadmap-only — they land with Part 5's benchmark tools.)
- `McpServerApplication` boot class: bounded scan (`io.diag.mcp` + `io.diag.evidence`), explicit `@EnableJdbcRepositories`, `@Import` of the two agent-core memory impls.

### Milestone M1: Tool Registration & Schema Mapping — ✅ DONE
- `DiagnosticMemoryMcpService` exposes `query_past_findings` (5-property JSON Schema, all optional).
- Maps incoming JSON parameters to domain `MemoryQuery`; `targetId` passes separately for provenance ranking.
- Connected to `DiagnosticMemoryRetriever` + `MemoryContextFormatter`.

### Milestone M2: Resource Providers — ✅ DONE (prompts dropped 2026-11-02, see Part 3)
- `DiagnosticMcpResourceProvider` exposes `diagnostics://hypotheses/guide` and `diagnostics://cases/{runId}` (full forensic JSON via `EvidenceService`).

### Milestone M3: Unit & Integration Test Suite (`McpServerGateTest`) — ✅ DONE, 11/11 green
- Fully offline: `PipedInputStream`/`PipedOutputStream` wired between `StdioServerTransportProvider` and a custom `PipedClientTransport` (in-process client↔server, no HTTP, no subprocess).
- `SimpleVectorStore` + a deterministic 4-dimension keyword embedding model stand in for pgvector + OpenAI; `StubEvidenceService` backs the cases resource.
- Gates: `initialize` handshake, `tools/list` schema shape, `tools/call` edge cases (all-null, `targetId`-only, >400-char symptom, >50-char template, invalid `category`, invalid `outcome`), happy-path 2×2 advisory matrix, and all three resource reads (guide, known run, unknown run → error JSON).
- Defense in depth observed: the SDK's `ToolInputValidator` rejects schema violations (e.g. `outcome=MAYBE`) server-side before the handler.

### Milestone M4: Claude Desktop / Cursor End-to-End Verification — ⬜ PENDING
- Create sample `claude_desktop_config.json` snippet demonstrating Streamable HTTP connectivity (`http://localhost:8085/api/v1/mcp`).
- Verify live tool invocation from an external MCP client against local PostgreSQL `pgvector`.
- Export documentation and update CV bullet points.
