-- Version history of query definitions (PRD FR-15).
CREATE TABLE query_version (
    id              UUID PRIMARY KEY,
    query_id        UUID         NOT NULL REFERENCES query_definition (id) ON DELETE CASCADE,
    version         INTEGER      NOT NULL,
    name            VARCHAR(100) NOT NULL,
    datasource_id   UUID         NOT NULL,
    sql_text        TEXT         NOT NULL,
    parameters_json TEXT         NOT NULL,
    changed_by      VARCHAR(100),
    changed_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    comment         VARCHAR(1000),
    UNIQUE (query_id, version)
);
