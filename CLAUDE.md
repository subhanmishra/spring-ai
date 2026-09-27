# CLAUDE.md

Orientation for `spring-ai-ragr`. This file is deliberately short: it is loaded into every session, so
it carries only what is needed *before* opening any file. Detailed context lives in
`.claude/context/`, loaded on demand — see **Context documents** below.

## Overview

A Spring Boot Retrieval-Augmented Generation (RAG) service. Users upload documents, which are parsed,
chunked, and stored as embeddings in a Postgres/pgvector store. A chat interface answers questions
grounded in the content of the uploaded documents, with chat history kept in Redis. Answer quality is
measured continuously on live traffic and on demand against a curated dataset. Three applications do
this - `ragr-ingest`, `ragr-app` (chat) and `ragr-eval` - sharing one Postgres and talking through the
vector store and a Kafka topic, never by calling each other, except the golden suite driving chat.

## Stack & versions

- **Java 26**, **Spring Boot 4.1.0** (parent), **Spring AI 2.0.1** (BOM)
- **LLM**: Ollama — `gemma4:e2b` for chat, `nomic-embed-text` for embeddings (768 dims). No API key;
  OpenAI config is present but commented out.
- **Vector store**: PostgreSQL + `pgvector`
- **Chat memory**: Redis (`spring-ai-model-chat-memory-repository-redis`)
- **Events**: Kafka (`spring-boot-starter-kafka`, broker `apache/kafka-native` in compose) — completed chat
  turns are published for evaluation to consume off the chat path
- **Migrations**: Flyway (`flyway-database-postgresql`) - ragr-ingest owns `public`, ragr-eval owns `eval`
- **API docs**: springdoc-openapi 3.1.0 (`springdoc-openapi-starter-webmvc-ui`)
- **Mapping**: modelmapper 3.2.4 (ragr-ingest)
- **Document parsing** (ragr-ingest): `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`, jsoup
- **Observability**: actuator, `micrometer-registry-prometheus`, `spring-boot-opentelemetry`,
  `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`,
  `opentelemetry-exporter-otlp`, `loki-logback-appender` (loki4j 2.0.3)
- Also: `spring-ai-vector-store-advisor`, optional runtime `spring-boot-docker-compose` /
  `spring-ai-spring-boot-docker-compose` (auto-starts `compose.yaml`)

## Build / run / test

```bash
./mvnw spring-boot:run -pl ragr-app -am    # chat service; auto-starts compose.yaml (pgvector, redis,
                                          # kafka, observability stack)
./mvnw spring-boot:run -pl ragr-ingest -am # ingestion, once ragr-app is up; owns the public schema
./mvnw spring-boot:run -pl ragr-eval -am   # evaluation, once ragr-app is up; every JVM's heap is
                                          # capped in its pom (IntelliJ configs need it too)
./mvnw test
./mvnw clean package
```

Those `spring-boot:run` lines are for people. A Claude session launches all three applications through
IntelliJ's run configurations instead, killing any running instance first - the `restart-app` skill.

```bash
./mvnw test -pl ragr-eval -am -Dsurefire.excludedGroups= -Dtest=EvalSuiteIT
```

The eval command is not the obvious one and `-Dgroups=eval` alone does not work — see
`.claude/context/evaluation.md`. It drives the running ragr-app over HTTP, so that must be up with the
corpus indexed; stop ragr-eval first on this host (memory), and expect minutes.

## Project structure

```
.
├── ragr-shared      # plain library, no Spring Boot; shared by all three applications
│   └── src/main/java/.../subhanmishra/
│       ├── chunk     # ChunkMetadata - the metadata keys every stored chunk carries
│       ├── citation  # Citation, CitationParser, CitationResolver, AnswerCitations
│       ├── event     # ChatTurnCompleted, TurnOrigin - the Kafka contract between chat and eval
│       └── exception # ApiExceptionHandler (abstract; each app's advice extends it),
│                     # ResourceNotFoundException
├── ragr-app         # the chat service (port 8080, actuator 9095); publishes turns to Kafka
│   └── src
│       ├── main
│       │   ├── java/.../subhanmishra/
│       │   │   ├── config      # SpringAiConfig, RedisConfig, OpenApiConfig, RagProperties (top-k,
│       │   │   │               # threshold), SpringAiProperties, KafkaConfig, EventsProperties
│       │   │   ├── controller  # ChatController, AdminDiagnosticsController, ConversationIdAdvice
│       │   │   ├── dto
│       │   │   ├── exception   # ChatExceptionHandler
│       │   │   └── service     # ChatService, RetrievalDiagnosticsService, ChatTurnPublisher
│       │   └── resources
│       │       ├── application.yaml          # active profile = dev
│       │       ├── application-dev.yaml      # everything else
│       │       ├── logback-spring.xml        # console + Loki appenders
│       │       └── docs/                     # spring-boot-reference.pdf, the golden corpus
│       └── test                              # SpringAiApplicationTests, ChatTurnPublisherTest,
│                                             # ChatControllerOriginTest
├── ragr-ingest      # ingestion, its own app (port 8081, actuator 9097): upload, parse, chunk, index
│   └── src
│       ├── main
│       │   ├── java/.../subhanmishra/
│       │   │   ├── config      # IngestionProperties (app.ingestion), ChunkingConfig,
│       │   │   │               # ThreadPoolConfig, ModelMapperConfig, OpenApiConfig
│       │   │   ├── controller  # DocumentController, BatchUploadStatusAdvice
│       │   │   ├── dto
│       │   │   ├── entity
│       │   │   ├── exception   # IngestExceptionHandler, DocumentProcessingException,
│       │   │   │               # UnsupportedDocumentTypeException
│       │   │   ├── repository
│       │   │   └── service     # DocumentParserService, DocumentIngestionService,
│       │   │       │           # DocumentMetadataService, DocumentHistoryService
│       │   │       └── parse   # ContentBlock (sealed: Prose | Table) and the Tika/XHTML path
│       │   │           └── pdf # PdfBlockReader: positioned text, table geometry, the two strippers
│       │   └── resources
│       │       ├── application.yaml          # all of it; no profiles; multipart limits
│       │       ├── logback-spring.xml
│       │       └── db/migration/             # Flyway V1, public schema incl. vector_store
│       └── test                              # IngestApplicationTests, parser tests
├── ragr-eval        # evaluation, its own app: consumes turns, scores, judges, runs the golden suite
│   └── src
│       ├── main
│       │   ├── java/.../subhanmishra/
│       │   │   ├── config      # EvalConfig, EvalProperties
│       │   │   ├── entity      # EvalRun, EvalCaseResult, EvalRunStatus
│       │   │   ├── repository
│       │   │   └── service     # OnlineEvalService (the Kafka listener), EvalScoringService,
│       │   │       │           # EvalMetricsService, GoldenEvalService
│       │   │       └── eval    # the score records, ContextPrecisionEvaluator, the golden dataset model
│       │   └── resources
│       │       ├── application.yaml          # all of it; no profiles. Port 9096, actuator only
│       │       ├── eval/golden-dataset.yaml  # curated regression cases
│       │       └── db/migration/             # Flyway V1, eval schema, own history table
│       └── test                              # EvalApplicationTests, scoring tests,
│                                             # EvalSuiteIT (@Tag("eval"), excluded from ./mvnw test)
├── docker/          # compose service config (grafana, loki, otel, pgadmin, prometheus, tempo)
├── docker-volume/   # gitignored runtime volume data, not source
├── .claude/         # gitignored; context documents + hooks (see below)
├── pom.xml          # parent POM: versions, module list, surefire eval exclusion
├── compose.yaml     # stays at the root; ragr-app runs from the root to find it, and owns it
├── CLAUDE.md        # this file
└── README.md        # user-facing quick-start; canonical for endpoint tables + infra ports/creds
```

## Architecture in brief

**Upload** (`ragr-ingest`, port 8081): `DocumentController` → `DocumentMetadataService` (creates the metadata record) →
`DocumentParserService` (file → `List<ContentBlock>` → chunks, on `documentProcessingPool`) →
`DocumentIngestionService` (enrich + write to pgvector, one virtual thread per batch) → status
`INDEXED`/`FAILED`, with every transition recorded by `DocumentHistoryService`.

**Chat** (`ragr-app`, port 8080): `ChatController` (base `/ai`) → `ChatService` → `ChatClient` → `QuestionAnswerAdvisor`
retrieves from pgvector → Ollama generates → history to Redis → `ChatTurnPublisher` sends the turn to
Kafka (`rag.chat.turn.completed`) without holding up the response.

**Evaluation** (`ragr-eval`, a separate process): `OnlineEvalService` consumes each turn → deterministic
scores on every turn, LLM judges on a sample → Micrometer, scraped by Prometheus from port 9096. The
golden suite drives ragr-app's real `/ai/generate` and reads its own turns back off the same topic.

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
| `ingestion.md` | batching, virtual threads, Hikari, retry/compensation, throughput measurements, model residency | `DocumentIngestionService`, `DocumentMetadataService`, `ThreadPoolConfig`, `IngestionProperties` |
| `chat-and-citations.md` | the citation header, `QA_PROMPT_TEMPLATE`, model choice, conversation semantics, the turn event | `ChatService`, `ChatController`, `SpringAiConfig`, `citation/**`, `event/**`, `ChatTurnPublisher` |
| `evaluation.md` | where eval runs (ragr-eval, Kafka) and why, online vs golden split, judge selection, metric registration, citation fabrication and resolution | `ragr-eval/**`, `service/eval/**`, `Eval*`, `EvalSuiteIT` |
| `observability.md` | compose stack, tracing/logging wiring, the four dashboards | `docker/**`, `compose.yaml`, `logback-spring.xml` |
| `api-and-errors.md` | `ApiExceptionHandler` and its two subclasses, upload validation, bulk upload, history, diagnostics | `controller/**`, `exception/**`, `dto/**` |

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
