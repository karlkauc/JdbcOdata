-- Keys for machine clients. A key acts as a service user on feeds or with an admin role on the REST API, never both.
CREATE TABLE api_key (
    id              UUID PRIMARY KEY,
    name            VARCHAR(80)  NOT NULL UNIQUE,
    key_prefix      VARCHAR(12)  NOT NULL,
    key_hash        VARCHAR(64)  NOT NULL UNIQUE,
    service_user_id UUID REFERENCES service_user (id) ON DELETE CASCADE,
    admin_role      VARCHAR(20),
    created_by      VARCHAR(100) NOT NULL,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT ck_api_key_target CHECK ((service_user_id IS NULL AND admin_role IS NOT NULL)
        OR (service_user_id IS NOT NULL AND admin_role IS NULL))
);
