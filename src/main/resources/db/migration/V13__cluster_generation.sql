-- Counter every instance polls: a change means another instance has invalidated something held in memory.
CREATE TABLE cluster_generation (
    topic      VARCHAR(50) PRIMARY KEY,
    generation BIGINT NOT NULL
);

INSERT INTO cluster_generation (topic, generation) VALUES ('result_cache', 0);
