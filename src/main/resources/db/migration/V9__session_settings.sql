-- Session settings (ALTER SESSION / SET after connect) per datasource with per-query overrides, and JDBC driver
-- properties per datasource. All three hold JSON objects of strings.
ALTER TABLE datasource ADD COLUMN session_settings TEXT;
ALTER TABLE datasource ADD COLUMN driver_properties TEXT;
ALTER TABLE query_definition ADD COLUMN session_settings TEXT;
