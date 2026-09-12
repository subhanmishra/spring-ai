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
| DELETE | `/ai/conversations` | Clear all stored chat memory |

Both generate endpoints return the conversation ID in an **`X-Conversation-Id`** response header — a freshly generated UUID when `conversationId` was not supplied. Pass it back on the next call to continue the same conversation.

```bash
curl "http://localhost:8080/ai/generate?prompt=Hello"

# Capture the conversation ID, then continue that conversation
curl -i "http://localhost:8080/ai/generate?prompt=Hello" | grep -i x-conversation-id
curl "http://localhost:8080/ai/generate?prompt=And%20what%20did%20I%20just%20ask?&conversationId=<id>"

curl "http://localhost:8080/ai/generateStream?prompt=Tell%20me%20a%20story"
```

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

Full OpenAPI docs are available via springdoc once the app is running (default: `/swagger-ui.html`).

## Infrastructure (`compose.yaml`)

* **pgvector** — PostgreSQL 16 + pgvector extension. Port `5432`, db `ragdatabase`, user `myuser` / `secret`.
* **pgadmin** — Postgres admin UI. Port `5050`, `admin@localhost.com` / `admin`.
* **redis** — Redis Stack, used as the chat memory store. Ports `6379` (Redis), `8001` (UI).
* **redis-exporter** — Exposes Redis metrics to Prometheus. Port `9121`.
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