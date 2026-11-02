-- Asynchronous OData requests (Prefer: respond-async). In the database, so that every instance can answer the status
-- monitor. The instance running a request refreshes heartbeat_at; without http_status the request is still running.
CREATE TABLE async_request (
    id           VARCHAR(32)  PRIMARY KEY,
    owner        VARCHAR(200) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    heartbeat_at TIMESTAMP WITH TIME ZONE NOT NULL,
    http_status  INTEGER,
    key_id       VARCHAR(16),
    payload      TEXT
);
CREATE INDEX ix_async_request_owner ON async_request (owner);
CREATE INDEX ix_async_request_created ON async_request (created_at);
