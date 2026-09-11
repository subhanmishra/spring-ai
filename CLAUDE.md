# CLAUDE.md

Persistent context for iterative work on `spring-ai-ragr`. Keep this in sync as the project evolves — it's the summary loaded at the start of every session; `GEMINI.md` is the longer-form companion doc.

## Overview

A Spring Boot Retrieval-Augmented Generation (RAG) service. Users upload documents, which are parsed, chunked, and stored as embeddings in a Postgres/pgvector store. A chat interface answers questions grounded in the content of the uploaded documents, with chat history kept in Redis.

## Stack & versions

- **Java 26**, **Spring Boot 4.1.0** (parent), **Spring AI 2.0.1** (BOM)
- **LLM**: Ollama (local model runner) — not OpenAI, despite what `README.md` says (stale)
- **Vector store**: PostgreSQL + `pgvector` extension
- **Chat memory**: Redis (`spring-ai-model-chat-memory-repository-redis`)
- **Migrations**: Flyway (`flyway-database-postgresql`)
- **API docs**: springdoc-openapi 3.1.0 (`springdoc-openapi-starter-webmvc-ui`)
- **Mapping**: modelmapper 3.2.4
- **Document parsing**: `spring-ai-pdf-document-reader`, `spring-ai-tika-document-reader`
- **Observability** (not documented in `GEMINI.md`'s dependency list — present in `pom.xml`): `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, `spring-boot-micrometer-tracing-opentelemetry`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `loki-logback-appender`, `logstash-logback-encoder`
- Also: `spring-ai-vector-store-advisor`, optional runtime `spring-boot-docker-compose` / `spring-ai-spring-boot-docker-compose` (auto-starts `compose.yaml`)

## Build / run / test

```bash
./mvnw spring-boot:run     # auto-starts compose.yaml (pgvector, redis, observability stack) via Spring Boot Docker Compose support
./mvnw test
./mvnw clean package
```

## Package layout

Standard Maven layout under `com.example.subhanmishra`:

```
src/main/java/.../subhanmishra/
  config/       # SpringAiConfig, ThreadPoolConfig, ...
  controller/   # ChatController, DocumentController
  dto/
  entity/
  exception/
  repository/
  service/      # ChatService, DocumentParserService, DocumentIngestionService,
                 # DocumentMetadataService, DocumentHistoryService
src/main/resources/
  application.yaml       # sets active profile to dev
  application-dev.yaml   # datasource, vector store, Ollama, RAG/chunking params
  logback-spring.xml
  db/migration/          # Flyway migrations (V1..V3, e.g. V3__Set_IST_Timezone.sql)
```

Key Java config:
- `SpringAiConfig` — `ChatClient` (system prompt, chat-memory + question-answer advisors), Redis `ChatMemoryRepository`, `TokenTextSplitter`.
- `ThreadPoolConfig` — custom `ForkJoinPool` (`documentProcessingPool`, threads named `doc-proc-pool-*`) used as a bulkhead for document parsing.

## Core workflows

**Document upload**: `DocumentController` → `DocumentMetadataService` creates metadata record → `DocumentParserService` parses into paragraphs in parallel on `documentProcessingPool` → `DocumentIngestionService` chunks and writes to pgvector → status updated to `INDEXED`/`FAILED`.

**Chat**: `ChatController` (`/api/chat/generate`, `/api/chat/generate-stream`) → `ChatService` → `ChatClient` → `QuestionAnswerAdvisor` retrieves relevant chunks from pgvector → Ollama generates the response → history persisted to Redis.

Document endpoints: `/api/documents/upload`, `/api/documents`, `/api/documents/{id}`.

## Docker environment (`compose.yaml`)

- **pgvector** (Postgres 16 + pgvector) — port `5432`, db `ragdatabase`, user `myuser` / `secret`
- **pgadmin** — port `5050`, `admin@localhost.com` / `admin`
- **redis** (Redis Stack) — ports `6379` (Redis), `8001` (UI)
- **otel-collector** — ports `4317` (gRPC), `4318` (HTTP), config: `docker/otel/otel-collector-config.yaml`
- **prometheus** — port `9090`, config: `docker/prometheus/prometheus.yml`
- **grafana** — port `3000`, datasources provisioned from `docker/grafana/provisioning/datasources/` (Prometheus, Loki, Tempo)
- **tempo** — port `3200`, config: `docker/tempo/tempo.yaml`
- **loki** — port `3100`, config: `docker/loki/local-config.yaml`

## Known stale docs

`README.md` is out of date — references OpenAI (actual: Ollama), Java 25 / Spring Boot 4.0.2 / Spring AI 2.0.0-M2 (actual: Java 26 / 4.1.0 / 2.0.1), and old `/ai/generate*` endpoints (actual: `/api/chat/*`, `/api/documents/*`). Don't treat it as a source of truth.