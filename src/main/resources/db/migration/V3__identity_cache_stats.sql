-- Milestone M3: service users, stored and fixed credentials, grants, cache, execution log, audit, admin users.

CREATE TABLE service_user (
    id                UUID PRIMARY KEY,
    username          VARCHAR(100) NOT NULL UNIQUE,
    password_hash     VARCHAR(100) NOT NULL,
    display_name      VARCHAR(200),
    force_passthrough BOOLEAN      NOT NULL DEFAULT FALSE,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at     TIMESTAMP WITH TIME ZONE
);

CREATE TABLE service_group (
    id          UUID PRIMARY KEY,
    name        VARCHAR(100) NOT NULL UNIQUE,
    description VARCHAR(1000)
);

CREATE TABLE service_group_member (
    group_id UUID NOT NULL REFERENCES service_group (id) ON DELETE CASCADE,
    user_id  UUID NOT NULL REFERENCES service_user (id) ON DELETE CASCADE,
    PRIMARY KEY (group_id, user_id)
);

CREATE TABLE stored_credential (
    id              UUID PRIMARY KEY,
    service_user_id UUID          NOT NULL REFERENCES service_user (id) ON DELETE CASCADE,
    datasource_id   UUID          NOT NULL REFERENCES datasource (id) ON DELETE CASCADE,
    db_username     VARCHAR(200)  NOT NULL,
    db_password_enc VARCHAR(4000) NOT NULL,
    key_id          VARCHAR(16)   NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (service_user_id, datasource_id)
);

CREATE TABLE fixed_credential (
    id              UUID PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL UNIQUE,
    description     VARCHAR(1000),
    datasource_id   UUID          NOT NULL REFERENCES datasource (id),
    db_username     VARCHAR(200)  NOT NULL,
    db_password_enc VARCHAR(4000) NOT NULL,
    key_id          VARCHAR(16)   NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

ALTER TABLE query_definition ADD COLUMN fixed_credential_id UUID REFERENCES fixed_credential (id);

CREATE TABLE query_grant (
    id             UUID PRIMARY KEY,
    query_id       UUID        NOT NULL REFERENCES query_definition (id) ON DELETE CASCADE,
    principal_type VARCHAR(20) NOT NULL,
    principal_id   UUID,
    granted_by     VARCHAR(100),
    granted_at     TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_query_grant_query ON query_grant (query_id);

CREATE TABLE cache_entry (
    id             UUID PRIMARY KEY,
    query_id       UUID         NOT NULL,
    query_version  INTEGER      NOT NULL,
    db_user        VARCHAR(200) NOT NULL,
    params_hash    VARCHAR(64)  NOT NULL,
    params_json    TEXT,
    payload        BYTEA        NOT NULL,
    row_count      INTEGER      NOT NULL,
    byte_size      INTEGER      NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    last_access_at TIMESTAMP WITH TIME ZONE NOT NULL,
    UNIQUE (query_id, query_version, db_user, params_hash)
);
CREATE INDEX ix_cache_entry_query ON cache_entry (query_id);
CREATE INDEX ix_cache_entry_expires ON cache_entry (expires_at);

CREATE TABLE execution_log (
    id                UUID PRIMARY KEY,
    started_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    finished_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    query_id          UUID,
    query_name        VARCHAR(100) NOT NULL,
    query_version     INTEGER,
    datasource_id     UUID,
    datasource_name   VARCHAR(100),
    caller            VARCHAR(200) NOT NULL,
    effective_db_user VARCHAR(200),
    execution_mode    VARCHAR(20),
    client            VARCHAR(20)  NOT NULL,
    user_agent        VARCHAR(500),
    client_ip         VARCHAR(100),
    parameters        TEXT,
    odata_options     VARCHAR(2000),
    cache_status      VARCHAR(20)  NOT NULL,
    db_duration_ms    BIGINT,
    total_duration_ms BIGINT       NOT NULL,
    row_count         INTEGER,
    bytes_sent        BIGINT,
    status            VARCHAR(30)  NOT NULL,
    error_message     VARCHAR(1000)
);
CREATE INDEX ix_execution_log_started ON execution_log (started_at);
CREATE INDEX ix_execution_log_query ON execution_log (query_id, started_at);

CREATE TABLE audit_log (
    id          UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    admin_user  VARCHAR(100) NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    entity_type VARCHAR(50)  NOT NULL,
    entity_id   VARCHAR(100),
    entity_name VARCHAR(200),
    details     TEXT
);
CREATE INDEX ix_audit_log_at ON audit_log (occurred_at);

CREATE TABLE admin_user (
    id            UUID PRIMARY KEY,
    username      VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at TIMESTAMP WITH TIME ZONE
);
