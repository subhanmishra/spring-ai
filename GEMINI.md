# Project Documentation for Gemini

This document provides a comprehensive overview of the `spring-ai-ragr` project, intended to ground AI agents and developers in the project's architecture, configuration, and dependencies.

## 1. Project Overview

This is a Spring Boot application that provides a Retrieval-Augmented Generation (RAG) service. It allows users to upload documents, which are then parsed, chunked, and stored in a vector database. A chat interface allows users to ask questions that are answered based on the content of the uploaded documents.

## 2. Project Structure

The project follows a standard Maven layout:

```
.
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── example
│   │   │           └── subhanmishra
│   │   │               ├── config      # Spring configuration classes
│   │   │               ├── controller  # Spring MVC controllers
│   │   │               ├── dto         # Data Transfer Objects
│   │   │               ├── entity      # JPA entities
│   │   │               ├── exception   # Custom exception classes
│   │   │               ├── repository  # Spring Data repositories
│   │   │               └── service     # Business logic
│   │   └── resources
│   │       ├── application.yaml    # Main application configuration
│   │       ├── application-dev.yaml # Development-specific configuration
│   │       └── db
│   │           └── migration     # Flyway database migrations
│   └── test
├── pom.xml         # Maven project configuration
├── compose.yaml    # Docker Compose setup
└── README.md
```

## 3. Dependencies (`pom.xml`)

The project uses the following key dependencies:

- **Spring Boot Starters**: `spring-boot-starter-webmvc`, `spring-boot-starter-validation`, `spring-boot-starter-data-jdbc`, `spring-boot-starter-flyway`
- **Spring AI**:
    - `spring-ai-starter-model-ollama`: Integration with the Ollama language model.
    - `spring-ai-starter-vector-store-pgvector`: Integration with the PgVector vector store.
    - `spring-ai-pdf-document-reader`: For parsing PDF documents.
    - `spring-ai-tika-document-reader`: For parsing other document types.
    - `spring-ai-model-chat-memory-repository-redis`: For storing chat history in Redis.
- **Database**: `flyway-database-postgresql` for database migrations.
- **API Documentation**: `springdoc-openapi-starter-webmvc-ui` for generating OpenAPI documentation.
- **Utilities**: `modelmapper` for object mapping.

## 4. Configuration

### 4.1. `application.yaml`

This is the main configuration file. It sets the active Spring profile to `dev`, which means that the properties in `application-dev.yaml` will be loaded and will override any properties in this file. It also configures the maximum file size for uploads.

### 4.2. `application-dev.yaml`

This file contains the primary configuration for the `dev` profile:

- **Datasource**: Configures the connection to the PostgreSQL database.
- **Spring AI**:
    - **Vector Store**: Configures the PgVector store, including table name, index type, and distance metric.
    - **Ollama**: Configures the connection to the Ollama service for both embedding and chat models.
    - **Chat Client**: Enables logging of prompts.
- **Logging**: Sets the logging level for various Spring AI components to `DEBUG`.
- **Application Properties (`app.*`)**:
    - **RAG**: Configures the chunking process (chunk size, overlap, etc.) and search parameters (top-k, similarity threshold).
    - **AI**: Configures the maximum number of messages to store in the chat history.

### 4.3. Java-based Configuration

- **`SpringAiConfig.java`**:
    - Configures the `ChatClient` with a system prompt and various advisors (e.g., for chat memory and question-answering).
    - Configures the `ChatMemoryRepository` to use Redis.
    - Configures the `TokenTextSplitter` with parameters from `application-dev.yaml`.
- **`ThreadPoolConfig.java`**:
    - Creates a custom `ForkJoinPool` named `documentProcessingPool` to handle document processing in a separate thread pool (bulkhead pattern).
    - The pool is configured to use a limited number of threads to avoid overwhelming the system.
    - Threads are named (`doc-chunk-pool-*`) for easier debugging.

## 5. Core Components

### 5.1. Services

- **`ChatService.java`**: Handles the generation of chat responses, including managing chat history with Redis.
- **`DocumentParserService.java`**: Parses uploaded documents (PDFs and other types) into paragraphs. It uses the custom `documentProcessingPool` to perform this work in parallel.
- **`DocumentIngestionService.java`**: Takes the parsed document stream, enriches it with metadata, and ingests the chunks into the PgVector store.
- **`DocumentMetadataService.java`**: Orchestrates the entire document upload and processing workflow. It creates metadata records, calls the parser and ingestion services, and updates the document status (e.g., `INDEXED`, `FAILED`). It also handles document deletion.
- **`DocumentHistoryService.java`**: Records the history of document processing statuses.

### 5.2. Controllers

- **`ChatController.java`**: Exposes endpoints for the chat interface (`/api/chat/generate`, `/api/chat/generate-stream`).
- **`DocumentController.java`**: Exposes endpoints for document management (`/api/documents/upload`, `/api/documents`, `/api/documents/{id}`).

## 6. Key Workflows

### 6.1. Document Upload and Processing

1.  A file is uploaded via the `DocumentController`.
2.  `DocumentMetadataService` creates an initial metadata record.
3.  `DocumentParserService` parses the file into paragraphs using a parallel stream running in the `documentProcessingPool`.
4.  `DocumentIngestionService` takes the stream of paragraphs, splits them into smaller chunks, and adds them to the PgVector store.
5.  `DocumentMetadataService` updates the document status to `INDEXED` or `FAILED`.

### 6.2. Chat

1.  A user sends a prompt to the `ChatController`.
2.  `ChatService` uses the `ChatClient` to generate a response.
3.  The `QuestionAnswerAdvisor` in the `ChatClient` queries the PgVector store for relevant document chunks.
4.  The retrieved chunks are added to the prompt as context.
5.  The Ollama language model generates a response based on the prompt and context.
6.  Chat history is maintained using Redis.

## 7. Docker Environment (`compose.yaml`)

The `compose.yaml` file defines the local development environment using Docker Compose. It includes the core application dependencies and a full observability stack.

### 7.1. Core Services

- **`pgvector`**: A PostgreSQL database (version 16) with the `pgvector` extension installed. It serves as the vector store for the RAG service.
    - **Port**: `5432`
    - **Credentials**: `myuser` / `secret`
    - **Database**: `ragdatabase`
- **`pgadmin`**: A web-based administration tool for PostgreSQL.
    - **Port**: `5050`
    - **Credentials**: `admin@localhost.com` / `admin`
- **`redis`**: A Redis Stack instance used for caching and as the `ChatMemoryRepository` to store chat history.
    - **Ports**: `6379` (Redis), `8001` (UI)

### 7.2. Observability Stack

The environment includes a comprehensive observability stack for monitoring the application.

- **`otel-collector`**: The OpenTelemetry Collector receives, processes, and exports telemetry data (traces, metrics, logs). It is configured via `docker/otel/otel-collector-config.yaml`.
    - **Ports**: `4317` (gRPC), `4318` (HTTP)
- **`prometheus`**: A monitoring system that collects and stores metrics as time-series data. It scrapes metrics from the Spring Boot application and other services.
    - **Port**: `9090`
- **`grafana`**: A visualization platform for creating dashboards to monitor metrics, logs, and traces. It is pre-configured with datasources for Prometheus, Tempo, and Loki.
    - **Port**: `3000`
- **`tempo`**: A high-volume, distributed tracing backend. It stores traces received from the `otel-collector`.
    - **Port**: `3200`
- **`loki`**: A log aggregation system designed to store and query logs. It collects logs from all services in the stack.
    - **Port**: `3100`
