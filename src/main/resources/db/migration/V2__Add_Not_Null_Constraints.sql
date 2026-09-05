-- V2__Add_Not_Null_Constraints.sql

-- Add NOT NULL constraints to document_metadata table
ALTER TABLE document_metadata
    ALTER COLUMN content_type SET NOT NULL,
    ALTER COLUMN status SET NOT NULL,
    ALTER COLUMN created_at SET NOT NULL;

-- Add NOT NULL constraint to the remaining non-primary key column in document_metadata_history
-- Note: document_metadata_id, status, and created_at are already NOT NULL from V1.
ALTER TABLE document_metadata_history
    ALTER COLUMN details SET NOT NULL;

-- Create a trigger function to prevent updates to the created_at column
CREATE OR REPLACE FUNCTION prevent_created_at_update()
RETURNS TRIGGER AS $$
BEGIN
    -- Check if the created_at value is being changed during an UPDATE operation
    IF NEW.created_at IS DISTINCT FROM OLD.created_at THEN
        RAISE EXCEPTION 'The created_at column cannot be updated.';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

-- Attach the trigger to the document_metadata table
CREATE TRIGGER trg_prevent_document_metadata_created_at_update
BEFORE UPDATE ON document_metadata
FOR EACH ROW
EXECUTE FUNCTION prevent_created_at_update();