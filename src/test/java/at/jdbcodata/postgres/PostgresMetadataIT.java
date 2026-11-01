package at.jdbcodata.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.cache.CacheKey;
import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.ServiceUser;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.ResultColumn;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.TypeMapper;
import at.jdbcodata.stats.StatsService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Runs the whole service with PostgreSQL as metadata DB: Flyway migrations, JPA mappings, cache blobs, stats SQL. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-pg;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret-pg",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=100000"
})
class PostgresMetadataIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Value("${local.server.port}")
    int port;

    @Autowired QueryService queries;
    @Autowired DatasourceService datasources;
    @Autowired ServiceUserService users;
    @Autowired ResultCache cache;
    @Autowired StatsService stats;
    @Autowired AuditService audit;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String user, String password) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes()));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void everythingWorksOnPostgres() throws Exception {
        assertThat(get("/actuator/health", null, null).body()).contains("UP");
        assertThat(queries.findEnabledByName("Sales").orElseThrow().hasSnapshot()).as("seed snapshot on PG").isTrue();
        assertThat(get("/odata/", "demo", "demo").body()).contains("Sales");
        HttpResponse<String> feed = get("/odata/Sales?$top=2&$filter=sale_month%20eq%201", "demo", "demo");
        assertThat(feed.statusCode()).as(feed.body()).isEqualTo(200);

        ServiceUser user = users.create(new ServiceUserService.ServiceUserSpec("svc_pg", null, "pg-password", false, true, List.of()));
        users.storeCredential(user.id(), datasources.findByName("demo").orElseThrow().id(), "demo", "demo");
        assertThat(get("/odata/Sales/$count", "svc_pg", "pg-password").body().trim()).isEqualTo("24");

        ResultColumn c = new ResultColumn("v", Types.INTEGER, "INTEGER", 10, 0, true, TypeMapper.map(Types.INTEGER, "INTEGER", 10, 0));
        ResultTable table = new ResultTable(List.of(c), List.of(new Object[] {1}, new Object[] {2}), false);
        CacheKey key = CacheKey.of(UUID.randomUUID(), 1, "pg", Map.of());
        cache.put(key, Map.of(), table, Duration.ofMinutes(1));
        cache.onQueriesChanged(null);
        assertThat(cache.get(key).orElseThrow().table().rows()).hasSize(2);
        cache.cleanup();

        StatsService.Summary summary = stats.summary(Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(60));
        assertThat(summary.executions()).isGreaterThanOrEqualTo(2);
        assertThat(stats.perDay(Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(60))).isNotEmpty();
        assertThat(stats.executions(new StatsService.Filter(Instant.now().minus(Duration.ofHours(1)), Instant.now().plusSeconds(60), "Sales", null, null), 10, 0)).isNotEmpty();
        assertThat(audit.latest(10)).isNotEmpty();
        assertThat(get("/api/v1/queries", "admin", "secret-pg").statusCode()).isEqualTo(200);
        assertThat(get("/admin/stats", "admin", "secret-pg").statusCode()).isEqualTo(200);
    }
}
