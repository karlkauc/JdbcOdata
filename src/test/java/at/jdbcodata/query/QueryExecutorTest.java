package at.jdbcodata.query;

import static at.jdbcodata.testutil.TestData.datasource;
import static at.jdbcodata.testutil.TestData.param;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbAuthenticationException;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.ExecutionIdentity;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class QueryExecutorTest {

    private static final String URL = "jdbc:h2:mem:exectest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final IdentityPoolManager pools = new IdentityPoolManager();
    private static final JdbcOdataProperties props = new JdbcOdataProperties(
            new JdbcOdataProperties.Admin("a", "b"), new JdbcOdataProperties.OData(100, 20), new JdbcOdataProperties.Seed(""));
    private static final QueryExecutor executor = new QueryExecutor(pools, props);
    private static final ExecutionIdentity identity = new ExecutionIdentity("u", "u", "p", ExecutionMode.CALLER);
    private static final Datasource ds = datasource("exec", URL);

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "u", "p"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE nums (n INTEGER)");
            for (int i = 1; i <= 10; i++) st.execute("INSERT INTO nums VALUES (" + i + ")");
        }
    }

    @AfterAll
    static void tearDown() {
        pools.closeAll();
    }

    @Test
    void executesWithBoundParameters() {
        QueryDefinition q = query("Nums", "SELECT n FROM nums WHERE n > :min ORDER BY n",
                List.of(param("min", ParameterType.INT, true, null)), List.of(), KeyMode.ROWID);
        ResultTable t = executor.execute(q, ds, identity, Map.of("min", 7L), 100, 10);
        assertThat(t.rows()).hasSize(3);
        assertThat(t.rows().get(0)[0]).isEqualTo(8);
    }

    @Test
    void failsWhenResultExceedsLimitButProbeDoesNot() {
        QueryDefinition q = query("Nums", "SELECT n FROM nums", List.of(), List.of(), KeyMode.ROWID);
        assertThatThrownBy(() -> executor.execute(q, ds, identity, Map.of(), 5, 10))
                .isInstanceOf(ResultTooLargeException.class)
                .hasMessageContaining("5 rows")
                .extracting("limit").isEqualTo(5);
        ResultTable probe = executor.probe(q, ds, identity, Map.of(), 10);
        assertThat(probe.rows()).hasSize(1);
        assertThat(probe.truncated()).isTrue();
    }

    @Test
    void translatesDatabaseErrors() {
        QueryDefinition bad = query("Bad", "SELECT nope FROM nums", List.of(), List.of(), KeyMode.ROWID);
        assertThatThrownBy(() -> executor.execute(bad, ds, identity, Map.of(), 10, 10))
                .isInstanceOf(DbExecutionException.class)
                .hasMessageContainingAll("nope", "SQLState")
                .extracting("timeout").isEqualTo(false);

        ExecutionIdentity wrong = new ExecutionIdentity("u", "u", "wrong", ExecutionMode.CALLER);
        QueryDefinition q = query("Nums", "SELECT n FROM nums", List.of(), List.of(), KeyMode.ROWID);
        assertThatThrownBy(() -> executor.execute(q, ds, wrong, Map.of(), 10, 10))
                .isInstanceOf(DbAuthenticationException.class)
                .hasMessageContaining("exec");
    }

    @Test
    void computesEffectiveLimits() {
        Datasource wide = new Datasource(UUID.randomUUID(), "w", null, URL, null, null, 0, 5000, 1, true,
                Instant.now(), Instant.now());
        QueryDefinition plain = query("P", "SELECT 1", List.of(), List.of(), KeyMode.ROWID);
        assertThat(executor.effectiveMaxRows(plain, wide)).isEqualTo(100);        // global limit wins
        assertThat(executor.effectiveTimeoutSec(plain, wide)).isEqualTo(20);      // datasource 0 → global default

        QueryDefinition limited = new QueryDefinition(UUID.randomUUID(), "L", null, wide.id(), "w", "SELECT 1",
                ExecutionMode.CALLER, false, 0, 5, 10, KeyMode.ROWID, 1, true, List.of(), List.of(), Instant.now(), Instant.now());
        assertThat(executor.effectiveMaxRows(limited, ds)).isEqualTo(10);
        assertThat(executor.effectiveTimeoutSec(limited, ds)).isEqualTo(5);
    }
}
