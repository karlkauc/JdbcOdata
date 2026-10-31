-- Usage statistics of the public demo (website/docs/security.md, section "Usage statistics"). Same columns as the
-- OData Online Viewer's usage_event, so the usage dashboard (app key jdbcodata) can use the shared viewer queries.
-- Only the event types and the meaning of a few columns differ. Apply once by hand as the owning role before
-- deploying; the application never issues DDL.

CREATE TABLE IF NOT EXISTS usage_event (
  event_id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  received_at      timestamptz NOT NULL DEFAULT now(),
  event_type       text        NOT NULL CHECK (event_type IN ('service_doc','metadata','data_query','batch')),
  visitor_hash     text,                 -- sha256(daily salt | ip | user-agent), first 32 hex chars; no raw IP
  country_code     char(2),              -- unused (NULL), no GeoIP
  user_agent       text,                 -- truncated to 255
  device           text,                 -- desktop | mobile | bot | unknown
  status_code      int,
  app_version      text,
  path             text,                 -- unused (NULL)
  referrer         text,                 -- unused (NULL)
  source           text,                 -- client: powerquery (Excel, Power BI) | odata_viewer | browser | script | other
  schema_name      text,                 -- unused (NULL)
  target_namespace text,                 -- data_query: published query name, else other
  input_bytes      int,                  -- unused (NULL)
  file_count       int,                  -- unused (NULL)
  element_count    int,                  -- unused (NULL)
  type_count       int,                  -- unused (NULL)
  diagnostic_count int,                  -- unused (NULL)
  error_count      int,                  -- unused (NULL)
  duration_ms      int,
  status           text,                 -- ok | denied | rate_limited | rejected | error
  error_detail     text,                 -- unused (NULL)
  odata_version    text,                 -- unused (NULL)
  utm_source       text,                 -- unused (NULL)
  utm_medium       text,                 -- unused (NULL)
  utm_campaign     text,                 -- unused (NULL)
  query_options    text,                 -- sorted names such as $filter,$top,param; never values
  auth_used        boolean,              -- an Authorization header was sent; never the user name
  csdl_format      text                  -- unused (NULL)
);

CREATE INDEX IF NOT EXISTS idx_usage_event_received  ON usage_event (received_at);
CREATE INDEX IF NOT EXISTS idx_usage_event_type_time ON usage_event (event_type, received_at);
CREATE INDEX IF NOT EXISTS idx_usage_event_visitor   ON usage_event (visitor_hash);
