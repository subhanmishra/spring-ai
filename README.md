# Spring AI Docker Model Runner

Demo project for Spring Boot using Spring AI.

## Overview

This project demonstrates how to use Spring AI with OpenAI and a Vector Store (PGVector). It includes a REST controller for chat generation and streaming, utilizing Chat Memory and Logging advisors.

## Prerequisites

*   Java 25
*   Maven
*   Docker (for PGVector and Docker Compose support)

## Configuration

You need to set your OpenAI API key. You can do this by setting an environment variable or adding it to your `application.properties` / `application.yaml`.

```bash
export SPRING_AI_OPENAI_API_KEY=your-api-key
```

## Running the Application

This project uses Spring Boot Docker Compose support. Ensure Docker is running, then start the application:

```bash
./mvnw spring-boot:run
```

The application will automatically start the required PostgreSQL (pgvector) container defined in `compose.yaml`.

## API Endpoints

The application exposes the following endpoints under `/ai`:

### Generate Chat Response
**URL:** `/ai/generate`
**Method:** `GET`
**Query Parameters:**
*   `prompt` (optional): The prompt to send to the AI. Default: "Tell me a joke".

**Example:**
```bash
curl "http://localhost:8080/ai/generate?prompt=Hello"
```

### Generate Stream Response
**URL:** `/ai/generateStream`
**Method:** `GET`
**Query Parameters:**
*   `prompt` (optional): The prompt to send to the AI. Default: "Tell me a joke".

**Example:**
```bash
curl "http://localhost:8080/ai/generateStream?prompt=Tell%20me%20a%20story"
```

## Infrastructure

The project includes a `compose.yaml` file that provisions:
*   **pgvector**: PostgreSQL database with the `pgvector` extension for vector storage.
    *   Port: 5432
    *   Database: `mydatabase`
    *   User: `myuser`
    *   Password: `secret`

## Technologies

*   Spring Boot 4.0.2
*   Spring AI 2.0.0-M2
*   OpenAI
*   PostgreSQL (PGVector)
*   Docker Compose
