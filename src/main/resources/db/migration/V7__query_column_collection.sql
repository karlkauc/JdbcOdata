-- Collection-valued properties: SQL arrays and JSON columns may be published as Collection(Edm.X) or as collection
-- of a complex type (see ARCHITECTURE.md, $select mit Optionen). element_type / structure_json are what the snapshot
-- detected, collection_kind is the admin's choice (NONE = plain property as before).
ALTER TABLE query_column ADD COLUMN element_type VARCHAR(50);
ALTER TABLE query_column ADD COLUMN structure_json TEXT;
ALTER TABLE query_column ADD COLUMN collection_kind VARCHAR(10) NOT NULL DEFAULT 'NONE';
