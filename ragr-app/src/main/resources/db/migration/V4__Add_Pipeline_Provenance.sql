-- V4__Add_Pipeline_Provenance.sql

-- Record which version of the ingestion pipeline produced a document's chunks.
--
-- Without this a document indexed two pipeline generations ago is indistinguishable from one
-- indexed today: both read INDEXED, and nothing says whether their chunks were produced with
-- citation headers, with paragraph coalescing, or with PDF table detection on. The pipeline has
-- already changed in all three of those ways, each time invalidating every document ingested
-- before it, and until now there was no way to list which documents were affected.
--
-- Both columns are nullable on purpose. A NULL means "unknown - ingested before provenance was
-- recorded", which is treated as stale: for those documents we genuinely do not know, and the
-- conservative reading is the useful one.
ALTER TABLE document_metadata
    ADD COLUMN IF NOT EXISTS pipeline_version  INT,
    ADD COLUMN IF NOT EXISTS pipeline_settings JSONB;
