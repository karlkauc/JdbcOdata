-- Snapshots behind OData delta links (Prefer: odata.track-changes), see ARCHITECTURE.md.
CREATE TABLE delta_snapshot (
    token       VARCHAR(40)  PRIMARY KEY,
    owner       VARCHAR(200) NOT NULL,
    scope_hash  VARCHAR(64)  NOT NULL,
    payload     BYTEA        NOT NULL,
    row_count   INTEGER      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_delta_snapshot_created ON delta_snapshot (created_at);
