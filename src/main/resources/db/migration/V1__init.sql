-- JdbcOdata metadata schema, milestone M1.
-- Written for PostgreSQL syntax; H2 runs in MODE=PostgreSQL.

CREATE TABLE datasource (
    id                  UUID PRIMARY KEY,
    name                VARCHAR(100)  NOT NULL UNIQUE,
    description         VARCHAR(1000),
    jdbc_url            VARCHAR(2000) NOT NULL,
    driver_class        VARCHAR(300),
    timezone            VARCHAR(100),
    default_timeout_sec INTEGER       NOT NULL DEFAULT 60,
    max_rows            INTEGER       NOT NULL DEFAULT 500000,
    max_concurrent      INTEGER       NOT NULL DEFAULT 10,
    enabled             BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE query_definition (
    id              UUID PRIMARY KEY,
    name            VARCHAR(100)  NOT NULL UNIQUE,
    description     VARCHAR(1000),
    datasource_id   UUID          NOT NULL REFERENCES datasource (id),
    sql_text        TEXT          NOT NULL,
    execution_mode  VARCHAR(20)   NOT NULL DEFAULT 'CALLER',
    cache_enabled   BOOLEAN       NOT NULL DEFAULT FALSE,
    cache_ttl_sec   INTEGER       NOT NULL DEFAULT 3600,
    timeout_sec     INTEGER,
    max_rows        INTEGER,
    key_mode        VARCHAR(20)   NOT NULL DEFAULT 'ROWID',
    version         INTEGER       NOT NULL DEFAULT 1,
    enabled         BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX ix_query_definition_datasource ON query_definition (datasource_id);

CREATE TABLE query_parameter (
    id            UUID PRIMARY KEY,
    query_id      UUID         NOT NULL REFERENCES query_definition (id) ON DELETE CASCADE,
    ordinal       INTEGER      NOT NULL,
    name          VARCHAR(100) NOT NULL,
    param_type    VARCHAR(20)  NOT NULL,
    required      BOOLEAN      NOT NULL DEFAULT FALSE,
    default_value VARCHAR(1000),
    description   VARCHAR(1000),
    sensitive     BOOLEAN      NOT NULL DEFAULT FALSE,
    UNIQUE (query_id, name)
);

CREATE TABLE query_column (
    id             UUID PRIMARY KEY,
    query_id       UUID         NOT NULL REFERENCES query_definition (id) ON DELETE CASCADE,
    ordinal        INTEGER      NOT NULL,
    column_name    VARCHAR(200) NOT NULL,
    property_name  VARCHAR(200) NOT NULL,
    jdbc_type      INTEGER      NOT NULL,
    jdbc_type_name VARCHAR(100),
    edm_type       VARCHAR(50)  NOT NULL,
    col_precision  INTEGER,
    col_scale      INTEGER,
    nullable       BOOLEAN      NOT NULL DEFAULT TRUE,
    is_key         BOOLEAN      NOT NULL DEFAULT FALSE,
    visible        BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (query_id, property_name)
);
