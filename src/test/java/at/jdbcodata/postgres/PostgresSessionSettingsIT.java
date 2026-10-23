package at.jdbcodata.postgres;

import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryExecutor;
import at.jdbcodata.testutil.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Session settings against a real PostgreSQL target: set_config must survive the executor's rollback. */
@Testcontainers(disabledWithoutDocker = true)
class PostgresSessionSettingsIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final IdentityPoolManager pools = new IdentityPoolManager();
    private static final QueryExecutor executor = new QueryExecutor(pools, new ConcurrencyLimiter(),
            TestData.props(100, 20, ""));

    @AfterAll
    static void tearDown() {
        pools.closeAll();
    }

    @Test
    void datasourceDefaultsAndQueryOverridesApply() {
        Datasource ds = new Datasource(UUID.randomUUID(), "pg", null, postgres.getJdbcUrl(), null, null, 30, 1000, 10,
                true, Instant.now(), Instant.now(), Map.of("TimeZone", "Europe/Vienna"), Map.of("ApplicationName", "jdbcodata-it"));
        ExecutionIdentity identity = new ExecutionIdentity(postgres.getUsername(), postgres.getUsername(),
                postgres.getPassword(), ExecutionMode.CALLER);
        String sql = "SELECT to_char(TIMESTAMPTZ '2026-10-23 10:00:00+00', 'HH24') AS h, current_setting('application_name') AS app";
        QueryDefinition q = query("D", sql, List.of(), List.of(), KeyMode.ROWID);
        for (int i = 0; i < 3; i++) { // same pooled connection after the rollback of the previous run
            Object[] row = executor.execute(q, ds, identity, Map.of(), 10, 10).rows().get(0);
            assertThat(row[0]).isEqualTo("12"); // CEST
            assertThat(row[1]).isEqualTo("jdbcodata-it");
        }
        QueryDefinition iso = new QueryDefinition(UUID.randomUUID(), "D", null, ds.id(), "pg", sql, ExecutionMode.CALLER,
                null, null, false, 3600, null, null, KeyMode.ROWID, 1, true, List.of(), List.of(), null, null, List.of(),
                false, Map.of("TimeZone", "UTC"));
        assertThat(executor.execute(iso, ds, identity, Map.of(), 10, 10).rows().get(0)[0]).isEqualTo("10");
        assertThat(executor.execute(q, ds, identity, Map.of(), 10, 10).rows().get(0)[0]).isEqualTo("12");
    }
}
