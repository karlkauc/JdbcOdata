-- Rows to lock across instances. A row lock ends with its connection, so a crashed instance cannot leave it behind.
CREATE TABLE cluster_lock (
    name VARCHAR(50) PRIMARY KEY
);

INSERT INTO cluster_lock (name) VALUES ('startup');
