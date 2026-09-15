# Spring AI RAG Service

A Spring Boot Retrieval-Augmented Generation (RAG) service. Upload documents, they get parsed, chunked, and stored as embeddings in a Postgres/pgvector store; a chat interface answers questions grounded in that content, with chat history kept in Redis.

## Overview

This project uses Spring AI with a locally-running **Ollama** model (no external API key required), a **PGVector** store for document embeddings, and **Redis** for chat memory. It exposes REST endpoints for document management and for chat generation (single-shot and streaming).

## Prerequisites

* Java 26
* Maven
* Docker (for pgvector, Redis, and the observability stack via Docker Compose)
* [Ollama](https://ollama.com) running locally with the `nomic-embed-text` (embedding) and `llama3.2` (chat) models pulled:

```bash
ollama pull nomic-embed-text
ollama pull llama3.2
```

## Configuration

No API key is needed — Ollama is called locally. The default connection is `http://localhost:11434` (`src/main/resources/application-dev.yaml`, `spring.ai.ollama.base-url`); override it there if Ollama runs elsewhere.

### Ollama: enable the integrated GPU

Ollama **ignores an integrated GPU unless you tell it not to**, and embedding then runs on the CPU. This is the single biggest lever on indexing speed — on a test machine it roughly doubled embedding throughput (~830 → ~1,750 tokens/sec), taking a 645-page PDF from 415s to 221s:

```bash
setx OLLAMA_IGPU_ENABLE 1      # Windows; export it on Linux/macOS
```

Fully quit and relaunch Ollama afterwards — it only reads its environment at startup. To confirm it took effect, look for `Vulkan0 model buffer size` (rather than `CPU model buffer size`) in Ollama's `server.log`.

Note that `OLLAMA_NUM_PARALLEL` does **not** help here: Ollama pins embedding models to a single slot regardless, so indexing throughput is bounded by how fast one runner embeds.

### Document processing

Indexing behaviour is tuned under `app.rag.*` in `application-dev.yaml`:

| Property | Default | Purpose |
|---|---|---|
| `chunk-size` | `400` | Target chunk size in **tokens**. Consecutive paragraphs are joined until adding the next would exceed it, so chunks actually reach this budget. Tables are chunked separately, by rows |
| `min-chunk-length-to-embed` | `100` | Chunks shorter than this many **characters** are merged into the chunk before them, never discarded. Raise it if single-line noise is polluting retrieval |
| `min-chunk-size-chars` | `150` | Where the splitter looks for a sentence boundary when cutting an over-budget chunk. Not a minimum chunk length |
| `max-embed-tokens` | `2048` | The embedding model's context. Only a single table row wider than this can exceed it, and the parser warns when one does |
| `table-detection` | `auto` | Recover tables from PDFs (`off`/`auto`/`lattice`/`stream`). `auto` picks per page: ruled pages take columns from the rules, unruled ones from text alignment. Set `off` to fall back to the plain page-text reader |
| `batch-size` | `35` | Chunks written to pgvector per batch. The cost it controls is *tokens* (~10k per batch at the current mean), so revisit it if you change `chunk-size` |
| `ingestion-concurrency` | `4` | Batches written in parallel. Must stay well below `spring.datasource.hikari.maximum-pool-size` |
| `top-k` / `similarity-threshold` | `5` / `0.6` | Retrieval settings used by the chat endpoints |

## Running the Application

This project uses Spring Boot's Docker Compose support. Ensure Docker (and Ollama) are running, then start the application:

```bash
./mvnw spring-boot:run
```

This automatically starts the containers defined in `compose.yaml` (pgvector, Redis, and the observability stack).

## API Endpoints

### Chat (`/ai`)

| Method | Path | Description |
|---|---|---|
| GET | `/ai/generate` | Single-shot chat response. Query params: `prompt` (default `"Tell me a joke"`), `conversationId` (optional) |
| GET | `/ai/generateStream` | Streaming chat response (SSE). Same query params as above |
| GET | `/ai/conversations` | List all active conversation IDs |
| GET | `/ai/conversations/{id}` | Read back one conversation's messages, oldest first |
| DELETE | `/ai/conversations/{id}` | Delete a single conversation |
| DELETE | `/ai/conversations` | Clear all stored chat memory |

Both generate endpoints return the conversation ID in an **`X-Conversation-Id`** response header — a freshly generated UUID when `conversationId` was not supplied. Pass it back on the next call to continue the same conversation.

```bash
curl "http://localhost:8080/ai/generate?prompt=Hello"

# Capture the conversation ID, then continue that conversation
curl -i "http://localhost:8080/ai/generate?prompt=Hello" | grep -i x-conversation-id
curl "http://localhost:8080/ai/generate?prompt=And%20what%20did%20I%20just%20ask?&conversationId=<id>"

curl "http://localhost:8080/ai/generateStream?prompt=Tell%20me%20a%20story"

# Reload a conversation, then drop just that one
curl "http://localhost:8080/ai/conversations/<id>"
curl -X DELETE "http://localhost:8080/ai/conversations/<id>"
```

**Reading a conversation back does not give you the whole transcript.** Chat memory keeps a rolling window of the last `app.ai.max-chat-messages` messages (10 by default) and trims on *write*, so older turns are already gone from Redis and cannot be recovered — by anything. The response reports `maxRetainedMessages` next to `messageCount` so a client can tell a short conversation apart from a truncated one; a `messageCount` equal to the limit means earlier turns were discarded. Measured: after 7 turns (14 messages) on one conversation, 10 remain and the first two turns are unrecoverable. Raise `app.ai.max-chat-messages` if longer history matters — at the cost of a larger prompt on every request, since the window is also what gets replayed to the model.

`GET` returns 404 when nothing is stored under the id, which is also how an already-cleared conversation reads — Redis keeps no tombstone to tell the two apart. `DELETE` of one conversation is idempotent and returns 204 whether or not the id existed.

### Documents (`/api/v1/documents`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/documents/upload` | Upload and index a single document (PDF, DOCX, TXT, MD, CSV) |
| POST | `/api/v1/documents/upload-multiple` | Upload and index multiple documents at once |
| GET | `/api/v1/documents` | List all uploaded documents and their indexing status |
| GET | `/api/v1/documents/{id}` | Get metadata for a specific document |
| DELETE | `/api/v1/documents/{id}` | Delete a document and purge its vector embeddings |

```bash
curl -F "file=@document.pdf" http://localhost:8080/api/v1/documents/upload
```

Uploads are **synchronous** — the request does not return until the document is fully indexed, and a large one takes minutes (a 645-page, 13.6MB PDF indexes in roughly 4 minutes with the GPU enabled). Set a generous client timeout. The response reports the number of chunks created.

Indexing is all-or-nothing: if any batch fails, every chunk already written for that document is removed and the document is marked `FAILED`, so a failed upload never leaves partial content to be retrieved. Re-uploading is the way to retry. Maximum upload size is 25MB per file (`spring.servlet.multipart` in `application.yaml`).

### Admin diagnostics (`/api/v1/admin`)

Operator-facing checks against the live corpus. **Registered only under the `dev` profile** — outside it these paths do not exist. There is no authentication in front of them, so if the `dev` profile is ever run somewhere reachable, block the `/api/v1/admin` prefix at the proxy.

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/admin/retrieval/search` | Show what the vector store returns for a query, without generating an answer |

Retrieval search runs the same similarity search the chat path runs, so it tells a *retrieval* failure apart from a *generation* failure — whether the passage an answer needed was never retrieved, or was retrieved and ignored.

```bash
curl -s -X POST http://localhost:8080/api/v1/admin/retrieval/search \
  -H 'Content-Type: application/json' \
  -d '{"query":"what is a spring boot starter"}'
```

`topK` and `similarityThreshold` default to the configured `app.rag` values the chat path uses, and the response echoes back whichever were in force. Override them to see what the threshold is excluding — `{"query":"...","topK":20,"similarityThreshold":0.0}` returns the near misses. Pass `documentId` to restrict the search to one document.

Each hit reports its `score` and the metadata written at ingestion time (`pageNumber`, `chunkIndex`, `blockType`, and for tables `tableIndex` / `tableRows`), plus `citation` and `text` — the two halves of the stored content. `hasCitationHeader: false` marks a chunk ingested before citation headers existed; the model cannot cite those, and re-ingesting the document is the fix.

Full OpenAPI docs are available via springdoc once the app is running (default: `/swagger-ui.html`).

## Infrastructure (`compose.yaml`)

* **pgvector** — PostgreSQL 16 + pgvector extension. Port `5432`, db `ragdatabase`, user `myuser` / `secret`.
* **pgadmin** — Postgres admin UI. Port `5050`, `admin@localhost.com` / `admin`.
* **redis** — Redis Stack, used as the chat memory store. Ports `6379` (Redis), `8001` (UI).
* **redis-exporter** — Exposes Redis metrics to Prometheus. Port `9121`.
* **postgres-exporter** — Exposes server-side Postgres metrics to Prometheus. Port `9187`. Runs with the `stat_user_tables` and `statio_user_indexes` collectors enabled so `vector_store` index-vs-sequential scan counts are visible.
* **otel-collector** — OpenTelemetry Collector. Ports `4317` (gRPC), `4318` (HTTP).
* **prometheus** — Metrics. Port `9090`.
* **grafana** — Dashboards (Prometheus/Loki/Tempo pre-provisioned). Port `3000`, anonymous access with Admin role (no login).
* **tempo** — Distributed tracing backend. Port `3200`.
* **loki** — Log aggregation. Port `3100`.

The application's own actuator endpoints run on a separate management port `9095` — `health`, `metrics` and `prometheus` are exposed (for example `http://localhost:9095/actuator/prometheus`), and that is what Prometheus scrapes.

Grafana is wired so you can move between signals: logs ↔ traces, metrics → traces (via exemplars on the `http_server_requests_*` panels), and metrics → logs (via a correlation on the `job` field, shown in Table view).

## Technologies

* Spring Boot 4.1.0
* Spring AI 2.0.1
* Ollama
* PostgreSQL (pgvector)
* Redis
* Flyway
* springdoc-openapi
* Docker Compose
* OpenTelemetry, Prometheus, Grafana, Tempo, Loki (observability)