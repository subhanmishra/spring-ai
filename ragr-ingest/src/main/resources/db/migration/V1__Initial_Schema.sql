-- V1__Initial_Schema.sql
--
-- The whole of the public schema that ragr-app owns: document metadata, its audit trail, and the
-- vector store. Evaluation keeps its own tables in the eval schema, migrated by ragr-eval.

-- pgcrypto for gen_random_uuid(); vector for the embedding column.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
CREATE EXTENSION IF NOT EXISTS vector;

-- One row per uploaded document.
--
-- The timestamps are TIMESTAMPTZ, which stores an absolute instant and has no zone of its own, so
-- there is no such thing as an "IST timestamptz". Rendering in IST is a read-time concern:
-- `created_at AT TIME ZONE 'Asia/Kolkata'`. Never write the abbreviation 'IST' - Postgres resolves it
-- to Israel Standard Time (+02:00) - and never default to `now() AT TIME ZONE <zone>`, which yields a
-- naive wall-clock reading that a TIMESTAMPTZ column then re-reads as UTC, shifting the instant.
CREATE TABLE IF NOT EXISTS document_metadata (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename      VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL,
    file_size     BIGINT,
    total_pages   INT,
    total_chunks  INT,
    status        VARCHAR(50)  NOT NULL,
    error_message TEXT,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ           DEFAULT now()
);

-- An immutable audit log of every status transition. There is deliberately no foreign key to
-- document_metadata: the trail has to survive the deletion of the document it describes.
CREATE TABLE IF NOT EXISTS document_metadata_history (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_metadata_id UUID        NOT NULL,
    status               VARCHAR(50) NOT NULL,
    details              TEXT        NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A document's creation time is fixed once written.
CREATE OR REPLACE FUNCTION prevent_created_at_update()
RETURNS TRIGGER AS $$
BEGIN
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'The created_at column cannot be updated.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE OR REPLACE TRIGGER trg_prevent_document_metadata_created_at_update
BEFORE UPDATE ON document_metadata
FOR EACH ROW
EXECUTE FUNCTION prevent_created_at_update();

-- Spring AI's PgVectorStore table (initialize-schema is off, so this is the only place it is created).
-- 768 dimensions for nomic-embed-text; changing the embedding model means changing this and
-- re-ingesting every document.
CREATE TABLE IF NOT EXISTS vector_store (
    id        UUID PRIMARY KEY,
    content   TEXT,
    metadata  JSONB,
    embedding vector(768)
);

-- No vector index, on purpose: every similarity search is exact.
--
-- An HNSW index is approximate, and at 12,444 chunks it lost real neighbours. For a question quoting
-- org.springframework.boot.actuate.health.HealthIndicator, the exact top 5 holds three chunks of the
-- upgrade map that answers it (0.765-0.747); the index, at its default hnsw.ef_search of 40, returned
-- none of them. The near-identical upgrade-map tables crowd one region of the graph. An exact search
-- costs 38 ms against the index's 1 ms - nothing beside the model's seconds.
--
-- Add an index back only when exact search is measurably slow. Then its operator class MUST match
-- spring.ai.vectorstore.pgvector.distance-type (COSINE_DISTANCE -> vector_cosine_ops), or the index is
-- never used; and re-check its recall against an exact search on the golden questions.
