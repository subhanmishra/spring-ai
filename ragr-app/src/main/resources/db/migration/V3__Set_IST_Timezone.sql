-- V3__Set_IST_Timezone.sql

-- Converts the timestamp columns to TIMESTAMPTZ. The USING clause reads the existing naive values
-- as UTC, which is what they were: V1 created them as plain TIMESTAMP and the application wrote
-- them through a UTC-based JDBC session.
--
-- The DEFAULT is plain now(), NOT `now() AT TIME ZONE 'IST'`, which is what this migration used to
-- say. That was wrong twice over:
--
--  1. 'IST' here is an ABBREVIATION, and Postgres resolves abbreviations from pg_timezone_abbrevs,
--     where IST is Israel Standard Time at +02:00 - not India at +05:30. The full zone name
--     'Asia/Kolkata' is the only spelling that means India; the abbreviation is ambiguous and
--     silently picks the wrong country.
--  2. Even with the right zone, `now() AT TIME ZONE <zone>` returns a TIMESTAMP WITHOUT TIME ZONE
--     holding that zone's wall-clock reading. Storing it back into a TIMESTAMPTZ column re-reads it
--     in the server's TimeZone (Etc/UTC here), so the stored instant is shifted by the offset rather
--     than being the current instant at all.
--
-- Measured before the fix, with an INSERT inside a rolled-back transaction: the defaulted value came
-- out at exactly now() + 02:00:00. Nothing in production was corrupted only because
-- DocumentMetadataService and DocumentHistoryService always set these columns explicitly, so the
-- DEFAULT never fired - it was a latent trap for the first insert that omitted them.
--
-- A TIMESTAMPTZ stores an absolute instant and has no zone of its own, so there is no such thing as
-- an "IST timestamptz" to store. Rendering in IST is a read-time concern: use
-- `created_at AT TIME ZONE 'Asia/Kolkata'` in a query, or set TimeZone on the session.

ALTER TABLE document_metadata
ALTER COLUMN created_at SET DATA TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
ALTER COLUMN created_at SET DEFAULT now();

ALTER TABLE document_metadata
ALTER COLUMN updated_at SET DATA TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE document_metadata_history
ALTER COLUMN created_at SET DATA TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
ALTER COLUMN created_at SET DEFAULT now();
