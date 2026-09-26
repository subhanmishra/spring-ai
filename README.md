# Spring AI RAG Service

A Spring Boot Retrieval-Augmented Generation (RAG) service. Upload documents, they get parsed, chunked, and stored as embeddings in a Postgres/pgvector store; a chat interface answers questions grounded in that content, with chat history kept in Redis.

## Overview

This project uses Spring AI with a locally-running **Ollama** model (no external API key required), a **PGVector** store for document embeddings, and **Redis** for chat memory. It exposes REST endpoints for document management and for chat generation (single-shot and streaming).

## Prerequisites

* Java 26
* Maven
* Docker (for pgvector, Redis, and the observability stack via Docker Compose)
* [Ollama](https://ollama.com) running locally with the `nomic-embed-text` (embedding) and `gemma4:e2b` (chat) models pulled:

```bash
ollama pull nomic-embed-text
ollama pull gemma4:e2b
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

### Evaluation

Answer quality is measured in two places, configured under `app.eval.*`.

**Live traffic is scored automatically.** Every real chat turn is checked against the context it was
actually given — how many citations it emitted, how many of those pointed at a page that was really
retrieved, whether anything was retrieved at all, whether the assistant refused. This is pure string
comparison over data already in the response, so it runs on 100% of turns and adds no measurable
latency. The results appear on the **"spring-ai-ragr — RAG evaluation"** Grafana dashboard.

A fraction of turns is additionally sent to two LLM judges (relevancy and groundedness). **Judging
never blocks the response** — the answer is already on its way back to the caller before a judgement
starts. It is still sampled, because Ollama serialises on one runner slot, so a judge call occupies
the chat model and the next user's generation queues behind it.

| Property | Default | Purpose |
|---|---|---|
| `enabled` | `true` | Master switch for all evaluation |
| `judge-model` | `gemma4:e2b` | Model used by the LLM judges — the chat model itself, see below |
| `online.judge-sample-rate` | `0.1` | Fraction of live answers sent to the judges. `0.0` keeps the free deterministic metrics and switches off the model calls |
| `online.max-concurrent-judgements` | `1` | Judgements in flight. Over this bound a judgement is **dropped and counted**, never queued |
| `golden.judged` | `false` | Whether a suite run also asks the judges. Roughly triples the run time |
| `golden.persist` | `true` | Write run and per-case rows to Postgres. Required for the dashboard's per-case tables **and** for its golden score panels |

**The judge is the chat model grading its own answers**, which makes these rates optimistic. A
dedicated judge would be better, but the smallest purpose-built one (`bespoke-minicheck`) needs
4.39 GiB and does not fit on a machine already holding the chat and embedding models. Read the judged
rates as a trend — a drop after a change is meaningful — rather than as an absolute quality score.

**The curated regression suite** replays a fixed set of questions with known-correct pages, which is
the only way to measure retrieval recall. It needs the corpus indexed and takes several minutes:

```bash
./mvnw test -Dsurefire.excludedGroups= -Dtest=EvalSuiteIT
```

Note that `-Dgroups=eval` on its own will **not** run it: a JUnit tag exclusion beats an inclusion, so
the exclusion itself has to be cleared. Results are written to the `eval_run` and `eval_case_result`
tables and picked up by the dashboard within 15 minutes (or on the next app restart).

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
| POST | `/ai/generate` | Single-shot chat response as JSON: the answer, its sources and citations. JSON body: `prompt` (required, max 4000 chars), `conversationId` (optional) |
| POST | `/ai/generateStream` | Streaming chat response (SSE): answer text, then `sources` and `done` events. Same JSON body as above |
| GET | `/ai/conversations` | List all active conversation IDs |
| GET | `/ai/conversations/{id}` | Read back one conversation's messages, oldest first |
| DELETE | `/ai/conversations/{id}` | Delete a single conversation |
| DELETE | `/ai/conversations` | Clear all stored chat memory |

Both generate endpoints return the conversation ID in an **`X-Conversation-Id`** response header — a freshly generated UUID when `conversationId` was not supplied. Pass it back on the next call to continue the same conversation.

**Citations come back as data, not as text.** The answer carries no inline `(file, p. N)` references; the evidence is reported beside it:

```json
{
  "answer": "Starters bundle a curated set of dependencies ...",
  "grounded": true,
  "sources": [
    { "ref": 1, "documentId": "8c1f…", "fileName": "spring-boot-reference.pdf", "page": 42,
      "blockType": "prose", "score": 0.83, "excerpt": "<the chunk's full text>", "cited": true }
  ],
  "citations": [
    { "fileName": "spring-boot-reference.pdf", "page": 42, "status": "VERIFIED", "sourceRef": 1, "writtenPage": null }
  ],
  "usage": { "model": "gemma4:e2b", "promptTokens": 2018, "completionTokens": 495, "latencyMillis": 56381 }
}
```

- `sources` — every chunk retrieved for the answer, in rank order, with its full text. `grounded: false` and an empty list mean the answer came from general knowledge.
- `citations` — each source the answer cites. `VERIFIED` points at a retrieved source; `REPAIRED` means the model wrote a section number where the page belongs (`writtenPage: "5.3"`) and it was resolved to that heading's page; `UNVERIFIED` points at nothing the answer was given.

`/ai/generateStream` streams the answer text as unnamed `data:` events, citations removed, then sends `event:sources` (`{grounded, sources}`) and `event:done` (`{citations, usage}`). A cancelled stream gets neither.

Because `/ai/generateStream` is a `POST`, a browser client **cannot** consume it with the native `EventSource` API, which only issues `GET` requests. Use `fetch` with a `ReadableStream` instead.

The prompt travels in a **JSON request body, not a query parameter** — a `GET` with `?prompt=` would put every question anyone asks into access logs, browser history and proxy logs, and would cap the prompt at whatever URL length the infrastructure allows. A blank or missing prompt, or one over 4000 characters, returns a 400 `ProblemDetail`.

```bash
curl -X POST http://localhost:8080/ai/generate \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Hello"}'

# Capture the conversation ID, then continue that conversation
curl -i -X POST http://localhost:8080/ai/generate -H 'Content-Type: application/json' -d '{"prompt":"Hello"}' | grep -i x-conversation-id
curl -X POST http://localhost:8080/ai/generate -H 'Content-Type: application/json' \
  -d '{"prompt":"And what did I just ask?","conversationId":"<id>"}'

curl -N -X POST http://localhost:8080/ai/generateStream \
  -H 'Content-Type: application/json' \
  -d '{"prompt":"Tell me a story"}'

# Reload a conversation, then drop just that one
curl "http://localhost:8080/ai/conversations/<id>"
curl -X DELETE "http://localhost:8080/ai/conversations/<id>"
```

**Reading a conversation back does not give you the whole transcript.** Chat memory keeps a rolling window of the last `app.ai.max-chat-messages` messages (10 by default) and trims on *write*, so older turns are already gone from Redis and cannot be recovered — by anything. The response reports `maxRetainedMessages` next to `messageCount` so a client can tell a short conversation apart from a truncated one; a `messageCount` equal to the limit means earlier turns were discarded. Measured: after 7 turns (14 messages) on one conversation, 10 remain and the first two turns are unrecoverable. Raise `app.ai.max-chat-messages` if longer history matters — at the cost of a larger prompt on every request, since the window is also what gets replayed to the model.

`GET` returns 404 when nothing is stored under the id, which is also how an already-cleared conversation reads — Redis keeps no tombstone to tell the two apart. `DELETE` of one conversation is idempotent and returns 204 whether or not the id existed.

### Documents (`/api/v1/documents`)

| Method | Path | Description |
|---|---|---|
| POST | `/api/v1/documents/upload` | Upload and index a single document (PDF, DOCX, XLSX, PPTX, HTML, TXT, MD, CSV) |
| POST | `/api/v1/documents/upload-multiple` | Upload and index multiple documents at once. 201 all indexed, 207 some failed, 422 none indexed |
| GET | `/api/v1/documents` | List all uploaded documents and their indexing status |
| GET | `/api/v1/documents/{id}` | Get metadata for a specific document |
| GET | `/api/v1/documents/{id}/history` | Get the document's processing history, oldest entry first |
| DELETE | `/api/v1/documents/{id}` | Delete a document and purge its vector embeddings |

```bash
curl -F "file=@document.pdf" http://localhost:8080/api/v1/documents/upload

# Several at once — one result per file, in the order sent
curl -F "files=@a.pdf" -F "files=@b.docx" http://localhost:8080/api/v1/documents/upload-multiple
```

**Bulk upload reports every file, including the ones that failed.** A file that cannot be processed gets a `FAILED` entry carrying its document id and the error, rather than being dropped from the response — so a batch of ten that returns seven successes also returns three failures, each identifying itself. Follow a failed entry's `id` to `/{id}/history` for the full trail. The status code summarises the batch: **201** when every file indexed, **207 Multi-Status** when some failed, **422** when none did.

Files are processed one at a time. Ollama serialises embedding regardless of how many requests arrive, so uploading concurrently would add contention without adding throughput.

**Supported types are checked before anything is stored.** `.pdf`, `.docx`, `.xlsx`, `.pptx`, `.html`/`.htm`, `.txt`, `.md` and `.csv` are accepted; anything else returns **415** and leaves no document record behind. The check reads the filename extension, falling back to the declared `Content-Type` only when the filename has no extension — clients frequently send `application/octet-stream`, so a declared type is treated as a fallback rather than as evidence.

This is a type filter, not a content scanner. A supported extension whose contents cannot actually be parsed — a corrupt PDF, say — still returns **422** and *does* leave a `FAILED` record with its history, because that is a processing failure rather than a rejected type. In a bulk upload a rejected file appears as a `FAILED` entry with a **null id**, since no document was ever created for it.

Uploads are **synchronous** — the request does not return until the document is fully indexed, and a large one takes minutes (a 645-page, 13.6MB PDF indexes in roughly 4 minutes with the GPU enabled). Set a generous client timeout. The response reports the number of chunks created.

Indexing is all-or-nothing: if any batch fails, every chunk already written for that document is removed and the document is marked `FAILED`, so a failed upload never leaves partial content to be retrieved. Re-uploading is the way to retry. Maximum upload size is 25MB per file (`spring.servlet.multipart` in `application.yaml`).

```bash
curl "http://localhost:8080/api/v1/documents/<id>/history"
```

Every document reports **pipeline provenance** — the `pipelineVersion` and the settings in force when it was ingested — plus a computed `stale` flag and a `staleReason`. A document is stale when its stored chunks differ from what the same source file would produce now, which is the question "does this need re-uploading?". Stale chunks cannot be corrected in place; the document has to be ingested again.

```bash
curl -s http://localhost:8080/api/v1/documents | grep -o '"stale":[a-z]*'
```

Only settings that change what gets **stored** count toward staleness — chunk sizing, table detection, the embedding model and vector dimensions. `batch-size`, `ingestion-concurrency`, `top-k` and `similarity-threshold` are deliberately excluded: the first two affect throughput only and the last two act at retrieval time, so including them would mark the whole corpus stale every time they are retuned. Documents ingested before provenance existed report `stale: true` with a reason saying so, because what produced them is genuinely unknown.

The history endpoint returns each status transition with the details recorded at the time — `UPLOADING` → `PROCESSING` → `INDEXED` on success, or `UPLOADING` → `FAILED` carrying the error message when parsing or indexing broke. It is the only place a failure reason is kept once a document has been removed.

**History outlives the document it describes.** `document_metadata_history` is an immutable audit log with deliberately no foreign key to `document_metadata`, so deleting a document removes its metadata and vector chunks while leaving the trail intact. The endpoint therefore still answers for a deleted document, reporting `documentExists: false`; it 404s only when no history exists for that id at all.

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
* **grafana** — Dashboards (Prometheus/Loki/Tempo/Postgres pre-provisioned). Port `3000`, anonymous access with Admin role (no login). Three dashboards, each grouped into collapsible rows and each answering a different question:
  * **overview** — *is the application healthy?* JVM runtime, HTTP / Spring MVC, HikariCP, and the backing services (Redis, Postgres).
  * **AI / RAG metrics** — *is the AI pipeline healthy?* Ollama model calls, token throughput, the RAG advisor chain, and the pgvector store.
  * **RAG evaluation** — *are the answers any good?* Live citation fidelity and retrieval quality, plus the curated regression suite.

  The Postgres datasource exists for the evaluation dashboard's per-case tables and reads `eval_run` / `eval_case_result`.
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