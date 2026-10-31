package at.jdbcodata.usage;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real requests into the real schema of the public demo: what ends up in the table, and what must not. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:meta-usage;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-usage;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.usage.hash-secret=test-secret",
        "spring.profiles.active=demo",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.cache.cleanup-interval-ms=3600000",
        "jdbcodata.security.rate-limit-per-minute=100000"
})
class UsageRecordingIT {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        postgres.start();
        try (Connection c = stats(); Statement s = c.createStatement()) {
            s.execute(Files.readString(Path.of("deploy/odata.status20.net/usage/usage_stats.sql")));
        }
        registry.add("jdbcodata.usage.db-url", postgres::getJdbcUrl);
        registry.add("jdbcodata.usage.db-user", postgres::getUsername);
        registry.add("jdbcodata.usage.db-password", postgres::getPassword);
    }

    private static Connection stats() throws Exception {
        return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @LocalServerPort
    int port;

    @Test
    void recordsRequestsWithoutValues() throws Exception {
        String auth = "Basic " + Base64.getEncoder().encodeToString("demo:demo".getBytes());
        HttpClient http = HttpClient.newHttpClient();
        String path = "/odata/Sales?p_year=2025&$top=1&$filter=customer%20eq%20'Geheim'";
        HttpResponse<String> feed = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", auth).header("User-Agent", "Microsoft.Data.Mashup (x)").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(feed.statusCode()).isEqualTo(200);
        http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/$metadata"))
                .header("User-Agent", "curl/8.18.0").build(), HttpResponse.BodyHandlers.ofString());

        List<String> rows = waitForRows(2);
        assertThat(rows).hasSize(2);
        String query = rows.stream().filter(r -> r.contains("event_type=data_query")).findFirst().orElseThrow();
        assertThat(query).contains("source=powerquery", "device=desktop", "target_namespace=Sales", "status=ok",
                "status_code=200", "query_options=$filter,$top,param", "auth_used=t");
        String metadata = rows.stream().filter(r -> r.contains("event_type=metadata")).findFirst().orElseThrow();
        assertThat(metadata).contains("source=script", "status=denied", "status_code=401", "auth_used=f");
        assertThat(String.join("\n", rows)).doesNotContain("Geheim", "2025", "demo", "p_year", "127.0.0.1", "0:0:0:0");
    }

    /** Every column as text, so the assertion covers the whole row and not only the columns the code writes. */
    private static List<String> waitForRows(int expected) throws Exception {
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < 100 && rows.size() < expected; i++) {
            Thread.sleep(100);
            rows.clear();
            try (Connection c = stats(); Statement s = c.createStatement();
                 ResultSet r = s.executeQuery("SELECT * FROM usage_event")) {
                ResultSetMetaData meta = r.getMetaData();
                while (r.next()) {
                    StringJoiner row = new StringJoiner(" ");
                    for (int col = 1; col <= meta.getColumnCount(); col++) {
                        row.add(meta.getColumnName(col) + "=" + r.getString(col));
                    }
                    rows.add(row.toString());
                }
            }
        }
        return rows;
    }
}
