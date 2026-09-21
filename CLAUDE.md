# CLAUDE.md

Orientation for `spring-ai-ragr`. This file is deliberately short: it is loaded into every session, so
it carries only what is needed *before* opening any file. Detailed context lives in
`.claude/context/`, loaded on demand — see **Context documents** below.

## Overview

A Spring Boot Retrieval-Augmented Generation (RAG) service. Users upload documents, which are parsed,
chunked, and stored as embeddings in a Postgres/pgvector store. A chat interface answers questions
grounded in the content of the uploaded documents, with chat history kept in Redis. Answer quality is
measured continuously on live traffic and on demand against a curated dataset.

## Stack & versions

- **Java 26**, **Spring Boot 4.1.0** (parent), **Spring AI 2.0.1** (BOM)
- **LLM**: Ollama — `gemma4:e2b` for chat, `nomic-embed-text` for embeddings (768 dims). No API key;
  OpenAI config is present but commented out.
- **Vector store**: PostgreSQL + `pgvector`
- **Chat memory**: Redis (`spring-ai-model-chat-memory-repository-redis`)
- **Migrations**: Flyway (`flyway-database-postgresql`)
- **API docs**: springdoc-openapi 3.1.0 (`springdoc-openapi-starter-webmvc-ui`)
- **Mapping**: modelmapper 3.2.4
- **Document parsing**: `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`, jsoup
- **Observability**: actuator, `micrometer-registry-prometheus`, `spring-boot-opentelemetry`,
  `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp`, `loki-logback-appender` (loki4j 2.0.3)
- Also: `spring-ai-vector-store-advisor`, optional runtime `spring-boot-docker-compose` /
  `spring-ai-spring-boot-docker-compose` (auto-starts `compose.yaml`)

## Build / run / test

```bash
./mvnw spring-boot:run     # auto-starts compose.yaml (pgvector, redis, observability stack)
./mvnw test
./mvnw clean package
```

```bash
./mvnw test -Dsurefire.excludedGroups= -Dtest=EvalSuiteIT
```

The eval command is not the obvious one and `-Dgroups=eval` alone does not work — see
`.claude/context/evaluation.md`. It needs the corpus indexed and Ollama up, and takes minutes.

## Project structure

```
.
├── src
│   ├── main
│   │   ├── java/.../subhanmishra/
│   │   │   ├── config      # SpringAiConfig, ThreadPoolConfig, RedisConfig, OpenApiConfig,
│   │   │   │               # ModelMapperConfig, JdbcConversionsConfig, RagProperties,
│   │   │   │               # SpringAiProperties, EvalConfig, EvalProperties
│   │   │   ├── controller  # ChatController, DocumentController, AdminDiagnosticsController
│   │   │   ├── dto
│   │   │   ├── entity
│   │   │   ├── exception
│   │   │   ├── repository
│   │   │   └── service     # ChatService, DocumentParserService, DocumentIngestionService,
│   │   │       │           # DocumentMetadataService, DocumentHistoryService,
│   │   │       │           # RetrievalDiagnosticsService, PipelineProvenanceService,
│   │   │       │           # EvalScoringService, EvalMetricsService, OnlineEvalService,
│   │   │       │           # GoldenEvalService
│   │   │       ├── eval    # CitationParser, CitationResolver, Citation, EvalScores,
│   │   │       │           # RetrievalScores, CitationScores, AnswerScores,
│   │   │       │           # ExpectationScores, GoldenCase, GoldenDataset,
│   │   │       │           # GoldenDatasetLoader
│   │   │       ├── provenance # PipelineProvenance (CURRENT_VERSION), PipelineSettings
│   │   │       └── parse   # ContentBlock (sealed: Prose | Table), XhtmlBlockParser,
│   │   │           │       # TableChunker, TokenCounter, ChunkMetadata,
│   │   │           │       # SupportedDocumentTypes
│   │   │           └── pdf # PdfBlockReader, PdfTableDetector, PdfLineExtractor,
│   │   │                   # PdfTextRunExtractor, TextRun, LineSegment,
│   │   │                   # PageFooterStripper, TocEntryStripper
│   │   └── resources
│   │       ├── application.yaml          # active profile = dev, multipart limits
│   │       ├── application-dev.yaml      # everything else
│   │       ├── logback-spring.xml        # console + Loki appenders
│   │       ├── eval/golden-dataset.yaml  # curated regression cases
│   │       └── db/migration/             # Flyway V1..V5
│   └── test                              # SpringAiApplicationTests, parser tests, eval tests,
│                                         # EvalSuiteIT (@Tag("eval"), excluded from ./mvnw test)
├── docker/          # observability stack config (grafana, loki, otel, pgadmin, prometheus, tempo)
├── docker-volume/   # gitignored runtime volume data, not source
├── .claude/         # gitignored; context documents + hooks (see below)
├── pom.xml
├── compose.yaml
├── CLAUDE.md        # this file
└── README.md        # user-facing quick-start; canonical for endpoint tables + infra ports/creds
```

## Architecture in brief

**Upload**: `DocumentController` → `DocumentMetadataService` (creates the metadata record) →
`DocumentParserService` (file → `List<ContentBlock>` → chunks, on `documentProcessingPool`) →
`DocumentIngestionService` (enrich + write to pgvector, one virtual thread per batch) → status
`INDEXED`/`FAILED`, with every transition recorded by `DocumentHistoryService`.

**Chat**: `ChatController` (base `/ai`) → `ChatService` → `ChatClient` → `QuestionAnswerAdvisor`
retrieves from pgvector → Ollama generates → history to Redis → `OnlineEvalService` scores the turn
after the response has already been returned.

Two facts belong here, by a narrow test: a fact earns a place in this section only if the mistake
it prevents happens in a file no route in `routes.json` covers. Everything else reaches you through
the router in time, and repeating it here is pure always-loaded cost.

- **Every chunk's stored text begins with a `[filename, p. N]` citation line.** It is embedded and
  persisted, not metadata. Anything that reads chunks back — export, re-ranking, re-chunking — must
  strip it. This is the only reason the model can cite page numbers at all. The document that owns
  this (`chat-and-citations.md`) is keyed to the chat path, so it will not fire for the export or
  migration code where the mistake actually gets made.
- **Do not use a bare `parallelStream()` for parse work.** It runs on the common pool and defeats the
  `documentProcessingPool` bulkhead. That pool is for parsing only; ingestion is I/O-bound and uses
  virtual threads bounded by a `Semaphore`.

## Context documents

Deep context is split into `.claude/context/`, which is **local and gitignored** — in a fresh clone
these files will not exist, and the pointers below are the map to rebuild rather than a promise.

A `PreToolUse` hook (`.claude/hooks/context-router.ps1`, mapped by `.claude/hooks/routes.json`)
injects the matching document the first time a session reads or edits a file in that area, once per
area per session. **If the hook has not fired, read the relevant file directly** — nothing else loads
these.

| document | covers | triggered by |
|---|---|---|
| `parsing.md` | `ContentBlock`, Tika/jsoup, PDF table geometry, strippers, the chunk-size budget | `service/parse/**`, `DocumentParserService` |
| `ingestion.md` | batching, virtual threads, Hikari, retry/compensation, throughput measurements, model residency | `DocumentIngestionService`, `DocumentMetadataService`, `ThreadPoolConfig` |
| `chat-and-citations.md` | the citation header, `QA_PROMPT_TEMPLATE`, model choice, conversation semantics | `ChatService`, `ChatController`, `SpringAiConfig` |
| `provenance.md` | `CURRENT_VERSION`, `PipelineSettings`, the two Jackson/ModelMapper traps | `PipelineProvenanceService`, `JdbcConversionsConfig` |
| `evaluation.md` | online vs golden split, judge selection, metric registration, citation fabrication and resolution | `service/eval/**`, `Eval*`, `EvalSuiteIT` |
| `observability.md` | compose stack, tracing/logging wiring, the three dashboards | `docker/**`, `compose.yaml`, `logback-spring.xml` |
| `api-and-errors.md` | `DocAiExceptionHandler`, upload validation, bulk upload, history, diagnostics | `controller/**`, `exception/**`, `dto/**` |

These documents are deliberately thin. The reasoning behind this codebase lives in its comments —
roughly a third of `src/main/java` is comment text, and classes like `EvalConfig`,
`DocumentIngestionService` and `CitationParser` carry the measurements behind each decision in their
javadoc, as `application-dev.yaml` and the files under `docker/` do for configuration. **The code is
the source; these documents are a thin index over it**, holding only the cross-file narrative and the
measurement history that no single class owns. When the two disagree, the code is right.

There is deliberately no document for configuration. `application-dev.yaml` comments itself at
length — read it directly.

Keeping this current is part of the work: when a package moves, update `routes.json`; when a fact
changes, edit the document rather than appending a correction to it.

## See also

`README.md` — user-facing quick-start, and the canonical source for API endpoint tables, curl
examples, and docker service ports and credentials. Do not duplicate those here.
