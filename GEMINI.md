# Project Overview

This project is a Spring Boot application that provides AI-powered chat and document analysis capabilities. It uses a Retrieval-Augmented Generation (RAG) architecture to answer questions based on a collection of documents.

## Key Features

*   **Chat:** Provides a conversational interface for interacting with the AI.
*   **Document Management:** Allows users to upload, manage, and query documents.
*   **RAG Pipeline:** Uses a RAG pipeline to retrieve relevant information from the document collection and generate answers.

## Technology Stack

*   **Backend:** Spring Boot
*   **AI:** Spring AI
*   **Language Model:** Ollama
*   **Vector Store:** Postgres with pgvector
*   **Chat Memory:** Redis
*   **Database:** Postgres
*   **Database Migrations:** Flyway
*   **API Documentation:** Springdoc OpenAPI

## Architecture

The application is divided into two main components:

*   **Chat:** The `ChatController` provides endpoints for generating chat responses. It uses a `ChatService` to interact with the `ChatClient` and `ChatMemory`.
*   **Document Management:** The `DocumentController` provides endpoints for managing documents. It uses a `DocumentMetadataService` to orchestrate the document processing pipeline, which includes parsing, ingestion, and storage.

## Services

The application uses the following services, which are defined in the `compose.yaml` file:

*   **`pgvector`:** A Postgres database with the `pgvector` extension for storing vector embeddings.
*   **`pgadmin`:** A web-based administration tool for the Postgres database.
*   **`redis`:** A Redis instance for storing chat memory.
