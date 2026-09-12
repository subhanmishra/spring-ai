# CLAUDE.md

Persistent context for iterative work on `spring-ai-ragr`. Keep this in sync as the project evolves — it's the summary loaded at the start of every session; `GEMINI.md` is the longer-form companion doc.

## Overview

A Spring Boot Retrieval-Augmented Generation (RAG) service. Users upload documents, which are parsed, chunked, and stored as embeddings in a Postgres/pgvector store. A chat interface answers questions grounded in the content of the uploaded documents, with chat history kept in Redis.

## Stack & versions

- **Java 26**, **Spring Boot 4.1.0** (parent), **Spring AI 2.0.1** (BOM)
- **LLM**: Ollama (local model runner) — `llama3.2` for chat, `nomic-embed-text` for embeddings (768 dims). No API key; OpenAI config is present but commented out.
- **Vector store**: PostgreSQL + `pgvector` extension
- **Chat memory**: Redis (`spring-ai-model-chat-memory-repository-redis`)
- **Migrations**: Flyway (`flyway-database-postgresql`)
- **API docs**: springdoc-openapi 3.1.0 (`springdoc-openapi-starter-webmvc-ui`)
- **Mapping**: modelmapper 3.2.4
- **Document parsing**: `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`
- **Observability**: `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `spring-boot-opentelemetry`, `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `loki-logback-appender` (loki4j 2.0.3)
- Also: `spring-ai-vector-store-advisor`, optional runtime `spring-boot-docker-compose` / `spring-ai-spring-boot-docker-compose` (auto-starts `compose.yaml`)

## Build / run / test

```bash
./mvnw spring-boot:run     # auto-starts compose.yaml (pgvector, redis, observability stack) via Spring Boot Docker Compose support
./mvnw test
./mvnw clean package
```

## Project structure

```
.
├── src
│   ├── main
│   │   ├── java/.../subhanmishra/
│   │   │   ├── config      # SpringAiConfig, ThreadPoolConfig, RedisConfig, OpenApiConfig,
│   │   │   │               # ModelMapperConfig, RagProperties, SpringAiProperties
│   │   │   ├── controller  # ChatController, DocumentController
│   │   │   ├── dto
│   │   │   ├── entity
│   │   │   ├── exception
│   │   │   ├── repository
│   │   │   └── service     # ChatService, DocumentParserService, DocumentIngestionService,
│   │   │                   # DocumentMetadataService, DocumentHistoryService
│   │   └── resources
│   │       ├── application.yaml       # sets active profile to dev
│   │       ├── application-dev.yaml   # datasource; pgvector store; Ollama (embedding + chat);
│   │       │                          # management.* actuator/tracing/metrics (port 9095);
│   │       │                          # BOTH Spring AI observation levels (see Observability);
│   │       │                          # app.rag.* chunking + search (top-k, similarity threshold);
│   │       │                          # app.ai.* max chat-history messages
│   │       ├── logback-spring.xml     # console + Loki appenders; traceId/spanId structured metadata
│   │       └── db/migration/          # Flyway migrations (V1..V3, e.g. V3__Set_IST_Timezone.sql)
│   └── test                           # only default SpringAiApplicationTests.java so far
├── docker/                            # config for the observability stack (see below)
│   ├── grafana/                       # grafana.ini + provisioning/{datasources,dashboards}
│   ├── loki/
│   ├── otel/
│   ├── pgadmin/
│   ├── prometheus/
│   └── tempo/
├── docker-volume/  # gitignored — runtime volume data (grafana plugins etc.), not source
├── pom.xml
├── compose.yaml
├── CLAUDE.md       # this file — auto-loaded context
├── GEMINI.md       # longer-form companion doc
└── README.md       # user-facing quick-start; canonical for endpoint tables + infra ports/creds
```

Key Java config:
- `SpringAiConfig` — `ChatClient` (system prompt, chat-memory + question-answer advisors), Redis `ChatMemoryRepository`, `TokenTextSplitter`.
- `ThreadPoolConfig` — custom `ForkJoinPool` (`documentProcessingPool`, threads named `doc-chunk-pool-*`) used as a bulkhead for document parsing. Parallelism is `availableProcessors / 2` (min 1), LIFO, with an uncaught-exception handler. Parallel work must be submitted to this pool explicitly — a bare `parallelStream()` runs on the common pool and defeats the bulkhead.

## Core workflows

**Document upload**: `DocumentController` → `DocumentMetadataService` creates metadata record → `DocumentParserService` splits into paragraphs then token-chunks, in parallel on `documentProcessingPool` → `DocumentIngestionService` enriches chunk metadata and writes to pgvector → status updated to `INDEXED`/`FAILED`. `DocumentHistoryService` records each status change as history. `DocumentMetadataService` also handles document deletion.

The parse → ingest hand-off is a **lazy stream, not a list**: `DocumentParserService.parse()` returns a `Map<String, Object>` holding a `documentStream` (`Stream<Document>`) plus `totalPages`, and `DocumentIngestionService.ingest()` consumes it, enriching metadata lazily and writing to the vector store in batches of 50. Chunk count is therefore only known after the stream is drained, which is why `totalPages` is carried separately rather than derived from the chunk list.

**Chat**: `ChatController` (base `/ai`) → `ChatService` → `ChatClient` → `QuestionAnswerAdvisor` retrieves relevant chunks from pgvector → Ollama generates the response → history persisted to Redis. The controller resolves the conversation ID (generating a UUID when none is supplied) and returns it in the `X-Conversation-Id` response header; `ChatService` does not generate IDs.

`DocumentController` is based at `/api/v1/documents`. Exact routes for both controllers are in `README.md`'s API Endpoints tables — kept canonical there, not duplicated here.

## Docker environment (`compose.yaml`)

Services: pgvector, pgadmin, redis, redis-exporter, otel-collector, prometheus, grafana, tempo, loki. Ports and credentials are documented in `README.md`'s Infrastructure section — don't duplicate them here, keep that as the canonical copy.

Config file locations (dev-context, not in README): `docker/otel/otel-collector-config.yaml`, `docker/prometheus/prometheus.yml`, `docker/grafana/grafana.ini`, `docker/grafana/provisioning/datasources/`, `docker/grafana/provisioning/dashboards/`, `docker/tempo/tempo.yaml`, `docker/loki/local-config.yaml`.

## Observability

The app exports metrics (Prometheus scrape on management port `9095`), traces (OTLP → otel-collector → Tempo) and logs (loki4j appender → Loki), and all four correlation directions work in Grafana. The wiring is non-obvious in several places, so before changing any of it:

- **Two independent Spring AI observation levels.** `spring.ai.chat.client.observations.*` logs the prompt as the caller wrote it, *before* advisors run. `spring.ai.chat.observations.*` logs the final prompt sent to Ollama, *after* `QuestionAnswerAdvisor` injects the retrieved context. Only the second shows the RAG context. Both are enabled; the handlers log at INFO.
- **`logback-spring.xml` must emit an empty traceId/spanId default, never a placeholder.** Loki drops structured metadata whose value is empty, so untraced lines carry no `traceId` label. A literal default such as `NONE` makes Grafana render a TraceID link on every line that then queries Tempo for a trace by that name and returns nothing.
- **Prometheus needs two CLI flags** (`compose.yaml`): `--enable-feature=exemplar-storage` or scraped exemplars are silently dropped, and `--web.enable-remote-write-receiver` or Tempo's metrics generator cannot write. Overriding `command:` also discards the image's default args, so `--storage.tsdb.path` must be restated.
- **Tempo's metrics generator is enabled per tenant** via `overrides.defaults.metrics_generator.processors`. Configuring the `metrics_generator` block alone does not switch it on.
- **Grafana datasource links**: Loki `derivedFields` needs an explicit `url` even when `datasourceUid` is set. Metrics → logs has no built-in link and uses a **correlation**, which is provisioned *inside* the datasource file — Grafana silently ignores a `provisioning/correlations/` directory.
- **Dashboard exemplars** require `"exemplar": true` per target. It is set on the `http_server_requests_seconds_*` targets only; gauges (JVM, Hikari) and redis-exporter series cannot carry exemplars.

## See also

`README.md` is the user-facing quick-start doc — canonical source for API endpoint tables/curl examples and docker service ports/credentials, kept in sync with this file. `GEMINI.md` is the longer-form architecture doc.