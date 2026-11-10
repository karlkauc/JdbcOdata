-- Passing the caller to the database for row-level security: on or off per datasource, a query may inherit,
-- switch it on or switch it off.
ALTER TABLE datasource ADD COLUMN pass_caller BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE query_definition ADD COLUMN pass_caller VARCHAR(10) NOT NULL DEFAULT 'INHERIT';
