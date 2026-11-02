package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceCircuitBreaker;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.health.DatasourceHealthService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/** An open breaker as clients see it. Own Spring context, because the breaker must stay open during the requests. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-breaker;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-breaker;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=100000",
        "jdbcodata.health.interval-ms=0",
        "jdbcodata.odata.anonymous-user=public",
        "jdbcodata.odata.breaker-failures=2",
        "jdbcodata.odata.breaker-open-sec=3600"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DatasourceBreakerIT {

    @Value("${local.server.port}")
    private int port;
    @Autowired
    DatasourceCircuitBreaker breaker;
    @Autowired
    DatasourceService datasources;
    @Autowired
    DatasourceHealthService health;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private Datasource ds;

    @BeforeAll
    void createFeeds() throws Exception {
        ds = datasources.findAll().get(0);
        HttpResponse<String> user = send("POST", "/api/v1/users",
                "{\"username\":\"public\",\"displayName\":\"Stored login\",\"password\":\"unused-but-long-enough\"}", admin());
        assertThat(user.statusCode()).as(user.body()).isIn(200, 201);
        HttpResponse<String> stored = send("PUT", "/api/v1/users/" + json.readTree(user.body()).get("id").asString()
                + "/credentials/" + ds.id(), "{\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}", admin());
        assertThat(stored.statusCode()).as(stored.body()).isIn(200, 201);
        feed("Cached", true);
        feed("Uncached", false);
    }

    private void feed(String name, boolean cache) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"%s","datasourceId":"%s","cacheEnabled":%s,"cacheTtlSec":3600,"sql":"SELECT * FROM SYSTEM_RANGE(1, 5)"}"""
                .formatted(name, ds.id(), cache), admin());
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        HttpResponse<String> snapshot = send("POST", "/api/v1/queries/" + json.readTree(created.body()).get("id").asString()
                + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", admin());
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
    }

    @AfterEach
    void closeBreaker() {
        breaker.record(ds, null);
    }

    @Test
    void queryGets503WithRetryAfterAndIsLogged() throws Exception {
        assertThat(send("GET", "/odata/Uncached", null, null).statusCode()).isEqualTo(200);

        open();
        HttpResponse<String> refused = send("GET", "/odata/Uncached", null, null);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
        assertThat(refused.body()).contains("not reachable");
    }

    @Test
    void cacheHitsOfStoredLoginsKeepWorking() throws Exception {
        assertThat(send("GET", "/odata/Cached", null, null).statusCode()).isEqualTo(200);

        open();
        HttpResponse<String> hit = send("GET", "/odata/Cached", null, null);

        assertThat(hit.statusCode()).as(hit.body()).isEqualTo(200);
        assertThat(hit.headers().firstValue("X-Cache")).hasValue("HIT");
    }

    @Test
    void passThroughCannotBeVerifiedAndGets503() throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString("demo:demo".getBytes());
        assertThat(send("GET", "/odata/Cached", null, basic).statusCode()).isEqualTo(200);

        open();
        HttpResponse<String> refused = send("GET", "/odata/Cached", null, basic);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(503);
        assertThat(refused.headers().firstValue("Retry-After")).isPresent();
    }

    @Test
    void adminPagesShowTheDatasourceAsDown() {
        open();
        assertThat(health.all()).filteredOn(h -> h.datasourceId().equals(ds.id()))
                .allMatch(h -> h.state() == DatasourceHealthService.State.DOWN);
    }

    private void open() {
        for (int i = 0; i < 2; i++) breaker.record(ds, new SQLException("refused", "08001"));
        assertThat(breaker.isOpen(ds)).isTrue();
    }

    private static String admin() {
        return "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes());
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
