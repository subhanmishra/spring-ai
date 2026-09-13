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
- `ThreadPoolConfig` — custom `ForkJoinPool` (`documentProcessingPool`, threads named `doc-chunk-pool-*`) used as a bulkhead for document parsing. Parallelism is `availableProcessors / 2` (min 1), LIFO, with an uncaught-exception handler. Parallel work must be submitted to this pool explicitly — a bare `parallelStream()` runs on the common pool and defeats the bulkhead. This pool is for **parsing only**: ingestion is I/O-bound and deliberately uses virtual threads instead (see below), so don't consolidate the two — blocking I/O on this pool would starve parsing.

## Core workflows

**Document upload**: `DocumentController` → `DocumentMetadataService` creates metadata record → `DocumentParserService` splits into paragraphs, coalesces them to the token budget, then token-chunks, in parallel on `documentProcessingPool` → `DocumentIngestionService` enriches chunk metadata and writes to pgvector → status updated to `INDEXED`/`FAILED`. `DocumentHistoryService` records each status change as history. `DocumentMetadataService` also handles document deletion.

The parse → ingest hand-off is a **lazy stream, not a list**: `DocumentParserService.parse()` returns a `Map<String, Object>` holding a `documentStream` (`Stream<Document>`) plus `totalPages`, and `DocumentIngestionService.ingest()` consumes it, enriching metadata lazily and partitioning it into batches of `app.rag.batch-size` chunks. Chunk count is therefore only known after the stream is drained, which is why `totalPages` is carried separately rather than derived from the chunk list.

**`app.rag.chunk-size` is a budget the parser fills, not a cap it happens to hit.** `DocumentParserService.coalesceParagraphs()` joins consecutive paragraphs until adding the next would exceed the budget. Without this step the earlier pipeline called `textSplitter.apply()` on each paragraph *individually*, so any paragraph under the budget passed through untouched and the effective chunk size was the paragraph size — a 645-page manual produced 7,289 chunks with a **median of 27 tokens** against a configured 400, 14% of them under 10 tokens (single words like `• WARN`). Things to know before touching it:

- Coalescing **must not span source documents**. `PagePdfDocumentReader` emits one document per page, so a group inherits exactly one page's metadata; `getEnrichedStream` puts `pageNumber` on every chunk and the system prompt asks the model to cite page numbers, so merging across pages yields wrong citations. This also floors the chunk count at one per page.
- Token counting uses jtokkit `CL100K_BASE` — deliberately the same as `TokenTextSplitter.DEFAULT_ENCODING_TYPE`, so the budget counted in the parser and the budget the splitter enforces cannot drift. Do not substitute a character-count approximation: PDF tables are space-padded, so chars-per-token varies wildly.
- `min-chunk-size-chars` is **not** a minimum chunk length — it is how far the splitter scans before looking for a sentence boundary when cutting an over-budget chunk. The only setting that actually discards small chunks is `min-chunk-length-to-embed`, and it is measured in **characters**.

**Ingestion is concurrent and not transactional as a whole.** `DocumentIngestionService.writeBatches()` runs one virtual thread per batch (`doc-ingest-*`), bounded by a `Semaphore` sized from `app.rag.ingestion-concurrency` — the permit count, not the thread count, is what protects the Hikari pool, since `vectorStore.add()` holds a connection across the Ollama embedding round-trip. Non-obvious consequences, all of them load-bearing:

- `ingest()` **must not** be `@Transactional`. A JDBC transaction is bound to one thread and one connection, so batch threads can never join it; an enclosing transaction would just pin an idle connection for the whole run while the real writes committed outside it.
- Each batch commits in its own `TransactionTemplate` (`REQUIRES_NEW`). All-or-nothing is preserved by **compensation**: any failure triggers `VectorStoreRepository.deleteByDocumentId(...)` — the same method `deleteDocument` uses — so a failed upload still leaves no chunks behind.
- The batch threads must be wrapped with a micrometer `ContextSnapshot` captured on the caller's thread. Without it they log with an empty `traceId` and the logs↔traces correlation silently breaks.
- **The first batch is written inline, before the fan-out, on purpose.** Ollama loads the embedding model lazily; requests arriving while its `llama-server` runner is still starting get proxied to a port nothing is listening on yet and fail with `dial tcp 127.0.0.1:<port>: ... refused` wrapped in an **HTTP 400**, which Spring AI maps to `NonTransientAiException` and therefore never retries. Writing one batch first guarantees the runner is loaded before any concurrency arrives, and `writeBatch` additionally retries (`app.rag.ingestion-max-attempts` / `ingestion-retry-backoff`) **only** for that failure, matched on the dial-error text since HTTP 400 is all Ollama gives to distinguish it — every other 400 still fails fast. Re-adding a batch is safe because the vector store upserts on chunk id. Cold starts are otherwise avoided by `spring.ai.ollama.embedding.keep-alive: "-1"` in `application-dev.yaml`, which pins the embedding model from the app's first embed call — no `OLLAMA_KEEP_ALIVE` env var needed, and scoped to the embedding model rather than machine-wide.
- `StructuredTaskScope` is the natural fit but is **still a preview API on JDK 26** (verify by compiling, not by reading the class file's minor version); adopting it would force `--enable-preview` on both compiler and launcher and pin class files to exactly Java 26.
- `spring.datasource.hikari.*` is now configured explicitly in `application-dev.yaml`. Note `minimum-idle` must stay below `maximum-pool-size` or `idle-timeout` never reaps anything, and `app.rag.ingestion-concurrency` must stay well below `maximum-pool-size` to leave connections for web requests and the `REQUIRES_NEW` history writes.
- **`app.rag.batch-size` counts chunks, but the cost it controls is tokens.** `vectorStore.add()` embeds *inside* the batch transaction, so a pooled connection is held for the whole Ollama round-trip — and with `ingestion-concurrency` batches queued against Ollama's single embedding slot, that wait multiplies. When paragraph coalescing raised mean tokens-per-chunk from 44 to 207, batch weight rose ~4.7x with nobody touching `batch-size`, and Hikari's `leak-detection-threshold` (60s) started firing: 2 warnings before, 8 after. They were false positives — every one was followed by `was returned to the pool`, with zero connection timeouts — but the fix is to keep batch *weight* sane (`batch-size: 50` ≈ 10k tokens) rather than to raise the threshold and leave connections parked for minutes. **Re-check `batch-size` whenever chunk sizing changes.**

**Ingestion throughput is bounded by Ollama's embedding speed, and `ingestion-concurrency` cannot raise it.** All of the following was measured on the same 13.6MB/645-page PDF, not inferred:

- **Ollama 0.34 pins embedding runners to a single slot unconditionally.** `OLLAMA_NUM_PARALLEL` appears honoured in Ollama's own server config yet is ignored for embedding models — verified by setting `OLLAMA_NUM_PARALLEL=4` with `OLLAMA_CONTEXT_LENGTH=512` so the budget fit `n_ctx_train=2048` exactly, and still getting `-np 1, n_slots = 1`. So `ingestion-concurrency > 1` overlaps the pgvector inserts but never the embedding calls.
- **Embedding is CPU-only by default and that is the bottleneck.** Ollama drops the integrated GPU unless `OLLAMA_IGPU_ENABLE=1` is set (it logs `dropping integrated GPU`). On this machine: ~830 tok/s across 8 CPU threads vs **~1,750 tok/s** on the Iris Xe via Vulkan, taking a full ingestion from 415.7s to 220.9s. It is an environment variable, so it is not captured in the repo — see `README.md`.
- **Cost scales with total tokens, not chunk or request count.** Paragraph coalescing cut chunk count by 79% but wall-clock by only ~19%, because it rearranges tokens without removing them. Do not expect batching or concurrency changes to move ingestion time.
- Connection-hold time follows `elapsed × ingestion-concurrency ÷ batches`. That is why halving elapsed time on the GPU removed the Hikari leak warnings outright (8 → 0) without changing `ingestion-concurrency`.

**Chat**: `ChatController` (base `/ai`) → `ChatService` → `ChatClient` → `QuestionAnswerAdvisor` retrieves relevant chunks from pgvector → Ollama generates the response → history persisted to Redis. The controller resolves the conversation ID (generating a UUID when none is supplied) and returns it in the `X-Conversation-Id` response header; `ChatService` does not generate IDs.

`DocumentController` is based at `/api/v1/documents`. Exact routes for both controllers are in `README.md`'s API Endpoints tables — kept canonical there, not duplicated here.

## Docker environment (`compose.yaml`)

Services: pgvector, pgadmin, redis, redis-exporter, postgres-exporter, otel-collector, prometheus, grafana, tempo, loki. The postgres-exporter collector flags are asymmetrically named — `--collector.stat_user_tables` but `--collector.statio_user_indexes` — and an unknown flag makes the container exit(1) rather than warn. Ports and credentials are documented in `README.md`'s Infrastructure section — don't duplicate them here, keep that as the canonical copy.

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