-- Connections per database login. 2 was the fixed value before this column existed.
ALTER TABLE datasource ADD COLUMN pool_size INTEGER NOT NULL DEFAULT 2;
