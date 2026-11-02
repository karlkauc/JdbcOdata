package at.jdbcodata.cache;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Counter in the metadata DB that tells the other instances to drop their in-memory results. */
@Component
class CacheGeneration {

    private final JdbcTemplate jdbc;

    CacheGeneration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    long current() {
        return jdbc.queryForObject("SELECT generation FROM cluster_generation WHERE topic = 'result_cache'", Long.class);
    }

    /** Part of the caller's transaction: other instances must not reload rows that are about to be deleted. */
    void bump() {
        jdbc.update("UPDATE cluster_generation SET generation = generation + 1 WHERE topic = 'result_cache'");
    }
}
