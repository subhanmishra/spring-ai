-- V3__Set_IST_Timezone.sql

-- Alter the document_metadata table to set the default timezone to IST for timestamp columns.
ALTER TABLE document_metadata
ALTER COLUMN created_at SET DATA TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
ALTER COLUMN created_at SET DEFAULT now() AT TIME ZONE 'IST';

ALTER TABLE document_metadata
ALTER COLUMN updated_at SET DATA TYPE TIMESTAMPTZ USING updated_at AT TIME ZONE 'UTC',
ALTER COLUMN updated_at SET DEFAULT now() AT TIME ZONE 'IST';

-- Alter the document_metadata_history table to set the default timezone to IST for the created_at column.
ALTER TABLE document_metadata_history
ALTER COLUMN created_at SET DATA TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC',
ALTER COLUMN created_at SET DEFAULT now() AT TIME ZONE 'IST';