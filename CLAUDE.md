# CLAUDE.md

Orientation for `spring-ai-ragr`, loaded into every session - so it holds only what is needed before
opening any file. Detail lives in the READMEs, in the code's own comments, and in `.claude/context/`,
loaded on demand (see **Context documents**).

## Overview

A Retrieval-Augmented Generation system on Spring Boot and Spring AI. Users upload documents, which are
parsed, chunked and stored as embeddings in Postgres/pgvector; a chat service answers from them, citing
pages, with history in Redis; and every answer is scored in a separate service, live and against a
curated dataset. Three applications - `ragr-ingest`, `ragr-app` (chat), `ragr-eval` - share one Postgres
and talk through the vector store and two Kafka topics, never by calling each other, except the golden
suite driving chat and ragr-eval reading chat's active-generations gauge so its judges wait for idle
chat. The design principles are at the top of `README.md`.

## Stack & versions

- **Java 26**, **Spring Boot 4.1.0** (parent), **Spring AI 2.0.1** (BOM)
- **Models**: Ollama - `gemma4:e2b` for chat and judging, `nomic-embed-text` for embeddings (768 dims,
  with task prefixes). No API key; the OpenAI config is commented out.
- **Data**: PostgreSQL + pgvector; Redis for chat memory; Kafka (`apache/kafka-native`) for turns and
  ratings; Flyway - ragr-ingest owns `public`, ragr-eval owns `eval`
- **Parsing** (ragr-ingest): Spring AI's PDF and Tika readers, jsoup; modelmapper 3.2.4
- **API docs**: springdoc-openapi 3.1.0
- **Observability**: actuator, Prometheus registry, OpenTelemetry tracing (OTLP), loki4j 2.0.3
- Spring Boot Docker Compose support (ragr-app only) starts `compose.yaml` on a host run

## Build / run / test

```bash
./mvnw spring-boot:run -pl ragr-app -am    # chat; starts compose.yaml's infrastructure
./mvnw spring-boot:run -pl ragr-ingest -am # ingestion, after ragr-app; owns the public schema
./mvnw spring-boot:run -pl ragr-eval -am   # evaluation, after ragr-app
./mvnw test
./mvnw clean package
```

Those lines are for people. **A Claude session launches the applications only through `ragr.ps1` (as
containers) or IntelliJ's run configurations, killing any running instance first** - the `ragr-run`
skill, whose default mode is in `.claude/run-mode`. Both modes use the same ports. Connection addresses
stay `localhost` in the YAML and containers override them in `compose.yaml`, so a change must work in
both modes. Every JVM has a capped heap and SerialGC.

```
/ragr-run                      start/restart what the current change needs, in the default mode
/ragr-run docker ingest        this run only, as a container; names: app, ingest, eval
/ragr-run intellij app eval    this run only, from IntelliJ
/ragr-run mode docker          change the default (.claude/run-mode)
/ragr-run status | stop [apps]
/ragr-run stack up|stop|down   the whole compose stack: infrastructure and all three containers
```

The golden suite - not the obvious command; `-Dgroups=eval` alone does not work:

```bash
./mvnw test -pl ragr-eval -am -Dsurefire.excludedGroups= -Dtest=EvalSuiteIT
```

It drives the running ragr-app over HTTP, so that must be up with the corpus stored; stop ragr-eval
first on this host (memory), send one warm-up chat turn after a ragr-app restart, and expect minutes.

## Project structure

```
.
├── ragr-shared      # plain library, no Spring Boot - the contracts between the applications
│   └── .../subhanmishra/
│       ├── chunk     # ChunkMetadata - the metadata keys every stored chunk carries
│       ├── citation  # Citation, CitationParser, CitationResolver, AnswerCitations
│       ├── embedding # TaskPrefixEmbeddingModel - the task prefix on every embedding request
│       ├── event     # ChatTurnCompleted, ChatFeedbackSubmitted, TurnOrigin - the Kafka messages
│       └── exception # ApiExceptionHandler (abstract; each app's advice extends it),
│                     # ResourceNotFoundException
├── ragr-app         # chat (8080, actuator 9095); publishes turns and ratings to Kafka
│   └── src/main
│       ├── java/.../subhanmishra/
│       │   ├── config      # SpringAiConfig (prompt, template, advisors), PooledQuestionAnswerAdvisor,
│       │   │               # RagProperties, SpringAiProperties, RedisConfig, KafkaConfig,
│       │   │               # EventsProperties, WebConfig, EmbeddingConfig (query prefix), OpenApiConfig
│       │   ├── controller  # ChatController, AdminDiagnosticsController, ConversationIdInterceptor
│       │   ├── dto, exception (ChatExceptionHandler)
│       │   └── service     # ChatService, ChatTurnPublisher, ChatFeedbackPublisher,
│       │                   # RetrievalDiagnosticsService
│       └── resources       # application.yaml (activates dev), application-dev.yaml (everything),
│                           # logback-spring.xml, docs/ (the reference manual, the golden corpus)
├── ragr-ingest      # ingestion (8081, actuator 9097): upload, parse, chunk, embed, store
│   └── src/main
│       ├── java/.../subhanmishra/
│       │   ├── config      # IngestionProperties (app.ingestion, pipeline version), ChunkingConfig,
│       │   │               # ThreadPoolConfig, EmbeddingConfig (passage prefix), ModelMapperConfig,
│       │   │               # OpenApiConfig
│       │   ├── controller  # DocumentController, BatchUploadStatusAdvice
│       │   ├── dto, entity, repository, exception (IngestExceptionHandler, two exceptions)
│       │   └── service     # DocumentMetadataService, DocumentParserService,
│       │       │           # DocumentIngestionService, DocumentHistoryService
│       │       └── parse   # ContentBlock (Prose | Table), the Tika/XHTML path, TableChunker
│       │           └── pdf # PdfBlockReader, table detection, the footer and contents strippers
│       └── resources       # application.yaml (all of it), logback-spring.xml,
│                           # db/migration (Flyway V1: public schema, vector_store)
├── ragr-eval        # evaluation (9096: actuator and human review)
│   └── src
│       ├── main/java/.../subhanmishra/
│       │   ├── config      # EvalConfig (the judges), EvalProperties, ObservationConfig,
│       │   │               # JudgeLineEndingAdvisor
│       │   ├── controller  # TurnReviewController (PUT /eval/turns/{id}/review)
│       │   ├── dto, entity (EvalRun, EvalCaseResult, EvalTurn, EvalTurnChunk), exception
│       │   ├── repository  # EvalTurnRepository (plain SQL: the queue, verdicts, feedback)
│       │   └── service     # OnlineEvalService, TurnJudgeWorker, TurnJudgeService, ChatIdleGate,
│       │       │           # FeedbackService, TurnReviewService, EvalRetentionService,
│       │       │           # EvalScoringService, EvalMetricsService, GoldenEvalService
│       │       └── eval    # the judges, RetrievalRanking, TurnVerdicts, TaskType, scores, dataset model
│       ├── main/resources  # application.yaml, eval/golden-dataset.yaml, db/migration (eval schema)
│       └── test            # unit tests, and EvalSuiteIT (@Tag("eval"), excluded from ./mvnw test)
├── docker/          # config for the compose services (grafana, loki, otel, pgadmin, prometheus, tempo)
├── docker-volume/   # gitignored runtime data
├── .claude/         # gitignored: context documents, hooks, skills
├── pom.xml          # parent: versions, modules, the surefire eval exclusion
├── compose.yaml     # at the root; infrastructure, plus the applications behind the `apps` profile
├── Dockerfile       # one layered image per application, from the jar packaged on the host
├── ragr.ps1         # stack | docker | intellij | status | logs
└── README.md        # the system as a whole; each module has its own README
```

## Two facts the router cannot deliver in time

A fact is here only if the mistake it prevents happens in a file no route in `routes.json` covers.

- **Every chunk's stored text begins with a `[filename, p. N]` citation line**, plus a `Section:` line
  when it starts mid-section. Both are embedded and stored. Anything reading chunks back - export,
  re-ranking, re-chunking - must remove them with `CitationParser.stripHeader`.
- **Never use a bare `parallelStream()` for parse work.** It runs on the common pool and bypasses
  `documentProcessingPool`, the bounded pool for parsing. Embedding and writing use virtual threads
  bounded by a `Semaphore`.

## Context documents

`.claude/context/` is **local and gitignored** - in a fresh clone these files will not exist, so read
this table as a map to rebuild, not a promise. A `PreToolUse` hook (`.claude/hooks/context-router.ps1`,
mapped by `.claude/hooks/routes.json`) injects a document the first time a session touches a file in its
area. **If it has not fired, read the file directly.**

| document | covers | triggered by |
|---|---|---|
| `parsing.md` | the pipeline's shape, tables, strippers, chunk-size evidence | `service/parse/**`, `chunk/**`, `DocumentParserService` |
| `ingestion.md` | batching, threads, memory, throughput, model residency | `DocumentIngestionService`, `DocumentMetadataService`, `IngestionProperties`, `ThreadPoolConfig`, `embedding/**`, `EmbeddingConfig` |
| `chat-and-citations.md` | the path, the citation convention, prompt and model evidence, generation settings | `ChatService`, `ChatController`, `SpringAiConfig`, `PooledQuestionAnswerAdvisor`, `citation/**`, `event/**`, `ChatTurnPublisher` |
| `evaluation.md` | where eval runs and why, the judges and their cost, calibration, the fabrication history | `ragr-eval/**`, `EvalSuiteIT` |
| `observability.md` | the compose stack, tracing and logging, dashboards, container measurements | `docker/**`, `compose.yaml`, `Dockerfile`, `ragr.ps1`, `logback-spring.xml` |
| `api-and-errors.md` | `ApiExceptionHandler` and its subclasses, upload validation, bulk upload, diagnostics | `controller/**`, `exception/**`, `dto/**` |

**Where things live.** The code states each rule and its reason; the READMEs are canonical for flows,
configuration and endpoints; the context documents hold what no single file owns - the cross-file
story and the measurements behind the choices. When they disagree, the code is right. When a package
moves, update `routes.json`; when a fact changes, edit the document rather than appending a correction.

## See also

- `README.md` - the system: design principles, architecture and flows, running, ports and
  credentials, dashboards.
- `ragr-ingest/README.md`, `ragr-app/README.md`, `ragr-eval/README.md`, `ragr-shared/README.md` - each
  module's flow, design choices, configuration, API and examples.

A fact belongs in the README of the module it describes; only what spans modules goes in the root.
