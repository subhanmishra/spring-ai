-- V1__Initial_Schema.sql

-- Enable the pgcrypto extension to use gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- Enable the vector extension if it's not already enabled.
CREATE EXTENSION IF NOT EXISTS vector;

-- Create the document_metadata table to store information about each document.
CREATE TABLE IF NOT EXISTS document_metadata (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    filename VARCHAR(255) NOT NULL,
    content_type VARCHAR(100),
    file_size BIGINT,
    total_pages INT,
    total_chunks INT,
    status VARCHAR(50),
    error_message TEXT,
    created_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE
);

-- Create the document_metadata_history table to act as an immutable audit log.
CREATE TABLE IF NOT EXISTS document_metadata_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    document_metadata_id UUID NOT NULL,
    status VARCHAR(50) NOT NULL,
    details TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

-- Create the vector_store table, which is used by Spring AI's PgVectorStore.
CREATE TABLE IF NOT EXISTS vector_store (
    id UUID PRIMARY KEY,
    content TEXT,
    metadata JSONB,
    embedding vector(768)
);

-- Create an HNSW index on the embedding column for fast similarity searches.
-- The operator class MUST match spring.ai.vectorstore.pgvector.distance-type. Spring AI's
-- PgDistanceType binds each distance type to one operator and one opclass, and an HNSW index
-- only answers the operator of its own opclass:
--   COSINE_DISTANCE    -> <=>  vector_cosine_ops   (what this project uses)
--   EUCLIDEAN_DISTANCE -> <->  vector_l2_ops
--   NEGATIVE_INNER_PRODUCT -> <#>  vector_ip_ops
-- This was vector_l2_ops while the app queried with <=>, so the index was never a candidate
-- and every similarity search fell back to a sequential scan.
CREATE INDEX IF NOT EXISTS vector_store_embedding_hnsw_idx ON vector_store USING HNSW (embedding vector_cosine_ops);