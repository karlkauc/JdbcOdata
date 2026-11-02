package at.jdbcodata.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.cluster.TwoNodes.Node;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.datasource.DatasourceSpec;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QuerySpec;
import at.jdbcodata.query.SchemaSnapshotService;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Two instances on one PostgreSQL metadata DB. What an admin changes on one has to reach the other. */
@Testcontainers(disabledWithoutDocker = true)
class TwoNodeIT {

    private static final String USER = "node";
    private static final String PASSWORD = "node-pw";

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private static Node first;
    private static Node second;

    @BeforeAll
    static void startNodes() throws Exception {
        first = TwoNodes.start(postgres, "cluster-demo");
        second = TwoNodes.start(postgres, "cluster-demo");
    }

    @AfterAll
    static void stopNodes() {
        if (second != null) second.close();
        if (first != null) first.close();
    }

    @Test
    void cacheInvalidationReachesTheOtherNode() throws Exception {
        String url = database(1);
        String name = "Cached" + suffix();
        Datasource ds = datasource(url);
        query(name, ds, true);

        assertThat(value(first, name)).isEqualTo(1);
        assertThat(value(second, name)).as("second node holds it in memory").isEqualTo(1);
        update(url, 2);
        assertThat(second.get("/odata/" + name, USER, PASSWORD).headers().firstValue("X-Cache")).hasValue("HIT");

        assertThat(first.send("DELETE", "/api/v1/cache", "admin", "secret-cluster").statusCode()).isEqualTo(200);

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline) && value(second, name) != 2) {
            Thread.sleep(100);
        }
        assertThat(value(second, name)).isEqualTo(2);
    }

    @Test
    void datasourceChangeReachesTheOtherNodeAtOnce() throws Exception {
        String oldUrl = database(1);
        String newUrl = database(2);
        String name = "Moved" + suffix();
        Datasource ds = datasource(oldUrl);
        query(name, ds, false);
        assertThat(value(second, name)).as("second node has a pool for the old URL").isEqualTo(1);

        first.bean(DatasourceService.class).update(ds.id(), spec(ds.name(), newUrl));

        assertThat(value(second, name)).isEqualTo(2);
    }

    private static int value(Node node, String query) throws Exception {
        HttpResponse<String> response = node.get("/odata/" + query, USER, PASSWORD);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return Integer.parseInt(response.body().replaceAll(".*\"V\":(\\d+).*", "$1"));
    }

    private static Datasource datasource(String url) {
        return first.bean(DatasourceService.class).create(spec("ds-" + suffix(), url));
    }

    private static DatasourceSpec spec(String name, String url) {
        return new DatasourceSpec(name, null, url, null, null, 30, 1000, 10, true);
    }

    private static void query(String name, Datasource ds, boolean cached) {
        QueryDefinition query = first.bean(QueryService.class).create(new QuerySpec(name, null, ds.id(), "SELECT v FROM t",
                ExecutionMode.CALLER, null, cached, 600, null, null, null, true, List.of()));
        first.bean(SchemaSnapshotService.class).snapshot(query, new ExecutionIdentity(USER, USER, PASSWORD, ExecutionMode.CALLER), Map.of());
    }

    /** An in-memory database whose first connection makes {@link #USER} its admin. */
    private static String database(int value) throws SQLException {
        String url = "jdbc:h2:mem:node-" + suffix() + ";DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE t (v INT)");
            s.execute("INSERT INTO t VALUES (" + value + ")");
        }
        return url;
    }

    private static void update(String url, int value) throws SQLException {
        try (Connection c = DriverManager.getConnection(url, USER, PASSWORD); Statement s = c.createStatement()) {
            s.execute("UPDATE t SET v = " + value);
        }
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
