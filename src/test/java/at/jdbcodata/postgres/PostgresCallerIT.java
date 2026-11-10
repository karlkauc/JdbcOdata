package at.jdbcodata.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.datasource.CallerContext;
import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.PassCaller;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryExecutor;
import at.jdbcodata.query.ResultCursor;
import at.jdbcodata.testutil.TestData;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Row-level security behind one technical account: the policy reads the caller and the groups the service passes,
 * so two callers of the same login get different rows, and a run without caller gets none.
 */
@Testcontainers(disabledWithoutDocker = true)
class PostgresCallerIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static final String SQL = "SELECT id FROM orders ORDER BY id";
    private static final IdentityPoolManager pools = new IdentityPoolManager();
    private static final QueryExecutor executor = new QueryExecutor(pools, new ConcurrencyLimiter(),
            TestData.props(100, 20, ""));
    private static Datasource ds;

    @BeforeAll
    static void createPolicy() throws Exception {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE TABLE orders (id INT, owner TEXT, region TEXT)");
            s.execute("INSERT INTO orders VALUES (1, 'anna', 'nord'), (2, 'bernd', 'sued'), (3, 'carla', 'nord')");
            s.execute("ALTER TABLE orders ENABLE ROW LEVEL SECURITY");
            s.execute("""
                    CREATE POLICY own_rows ON orders USING (
                      owner = current_setting('jdbcodata.caller', true)
                      OR region = ANY (string_to_array(current_setting('jdbcodata.groups', true), ',')))""");
            s.execute("CREATE ROLE gateway LOGIN PASSWORD 'gateway-pw'");
            s.execute("GRANT SELECT ON orders TO gateway");
        }
        // one connection: a caller left over from the previous run would show at once
        ds = new Datasource(UUID.randomUUID(), "pg", null, postgres.getJdbcUrl(), null, null, 30, 1000, 10, true,
                Instant.now(), Instant.now(), Map.of(), Map.of(), 1, true);
    }

    @AfterAll
    static void tearDown() {
        pools.closeAll();
    }

    private static ExecutionIdentity as(String name, String... groups) {
        return new ExecutionIdentity(name, "gateway", "gateway-pw", ExecutionMode.FIXED,
                new CallerContext(name, List.of(groups)));
    }

    private static List<Object> ids(QueryDefinition q, ExecutionIdentity identity) {
        return executor.execute(q, ds, identity, Map.of(), 10, 10).rows().stream().map(r -> r[0]).toList();
    }

    @Test
    void theSameTechnicalAccountReturnsEachCallerItsOwnRows() {
        QueryDefinition q = TestData.query("Orders", SQL, PassCaller.INHERIT);
        for (int i = 0; i < 2; i++) {
            assertThat(ids(q, as("anna"))).containsExactly(1);
            assertThat(ids(q, as("bernd"))).containsExactly(2);
            assertThat(ids(q, as("dora", "nord"))).containsExactly(1, 3);
        }
    }

    @Test
    void withoutCallerThePolicyLetsNothingThrough() {
        QueryDefinition on = TestData.query("Orders", SQL, PassCaller.INHERIT);
        QueryDefinition off = TestData.query("Orders", SQL, PassCaller.OFF);
        assertThat(ids(on, as("dora", "nord"))).containsExactly(1, 3);
        assertThat(ids(off, as("dora", "nord"))).isEmpty();
        assertThat(ids(on, new ExecutionIdentity("admin", "gateway", "gateway-pw", ExecutionMode.FIXED))).isEmpty();
        assertThat(ids(on, as("anna"))).containsExactly(1);
    }

    @Test
    void theCursorPathUsesTheCaller() {
        QueryDefinition q = TestData.query("Orders", SQL, PassCaller.INHERIT);
        try (ResultCursor cursor = executor.open(q, ds, as("bernd"), Map.of(), 10, 10)) {
            List<Object> ids = new ArrayList<>();
            for (Object[] row = cursor.next(); row != null; row = cursor.next()) ids.add(row[0]);
            assertThat(ids).containsExactly(2);
        }
        assertThat(ids(TestData.query("Orders", SQL, PassCaller.INHERIT), as("anna"))).containsExactly(1);
    }
}
