-- Single JSON objects as complex values (structure_single) and an additional text property for structured columns
-- (text_copy, for Excel).
ALTER TABLE query_column ADD COLUMN structure_single BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE query_column ADD COLUMN text_copy BOOLEAN NOT NULL DEFAULT FALSE;
