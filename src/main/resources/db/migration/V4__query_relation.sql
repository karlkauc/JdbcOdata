-- Relations between queries, published as OData navigation properties (metadata only).
CREATE TABLE query_relation (
    id             UUID PRIMARY KEY,
    query_id       UUID          NOT NULL REFERENCES query_definition (id) ON DELETE CASCADE,
    ordinal        INTEGER       NOT NULL,
    name           VARCHAR(100)  NOT NULL,
    target_query   VARCHAR(100)  NOT NULL,
    local_columns  VARCHAR(1000) NOT NULL,
    target_columns VARCHAR(1000) NOT NULL,
    to_many        BOOLEAN       NOT NULL DEFAULT FALSE,
    partner        VARCHAR(100),
    description    VARCHAR(1000),
    UNIQUE (query_id, name)
);
