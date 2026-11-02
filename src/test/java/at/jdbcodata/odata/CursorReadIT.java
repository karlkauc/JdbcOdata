package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.datasource.ConcurrencyLimiter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Whole results that stay in the database until they are written. Own Spring context for the small limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-cursor;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-cursor;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=100000",
        "jdbcodata.odata.max-rows=100",
        "jdbcodata.odata.response-rows-budget=30",
        "jdbcodata.odata.max-response-mb=3"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CursorReadIT {

    private static final String DEMO = basic("demo", "demo");
    private static final String ADMIN = basic("admin", "secret");
    private static final String BIG_ROWS = "SELECT REPEAT('x', 100000) AS payload FROM SYSTEM_RANGE(1, 50)"; // 5 MB

    @Value("${local.server.port}")
    private int port;
    @Autowired
    ConcurrencyLimiter limiter;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void createFeeds() throws Exception {
        String datasource = json.readTree(send("/api/v1/datasources", ADMIN).body()).get(0).get("id").asString();
        feed("Long", "SELECT * FROM SYSTEM_RANGE(1, 1000)", datasource, "\"cacheEnabled\":false");
        feed("Capped", "SELECT * FROM SYSTEM_RANGE(1, 1000)", datasource, "\"cacheEnabled\":false,\"maxRows\":500");
        feed("Broken", "SELECT 1 / (ROWNUM() - 1000) AS r FROM SYSTEM_RANGE(1, 1000)", datasource, "\"cacheEnabled\":false");
        feed("Oversized", BIG_ROWS, datasource, "\"cacheEnabled\":false");
        feed("OversizedCached", BIG_ROWS, datasource, "\"cacheEnabled\":true,\"cacheTtlSec\":60");
    }

    private void feed(String name, String sql, String datasource, String options) throws Exception {
        HttpResponse<String> created = post("/api/v1/queries", """
                {"name":"%s","datasourceId":"%s",%s,"sql":"%s"}""".formatted(name, datasource, options, sql));
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        HttpResponse<String> snapshot = post("/api/v1/queries/" + json.readTree(created.body()).get("id").asString()
                + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}");
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
    }

    @AfterEach
    void noSlotStaysTaken() throws Exception {
        for (int i = 0; i < 100 && limiter.usedPermits() > 0; i++) Thread.sleep(50);
        assertThat(limiter.usedPermits()).isZero();
    }

    /** {@code Prefer} sends the same request down the path that holds the table. */
    @ParameterizedTest
    @ValueSource(strings = {"/odata/Sales", "/odata/Sales?$select=customer,amount", "/odata/Sales?$format=application/xml",
            "/odata/Sales?$format=application/json;odata.metadata=full", "/odata/Sales?$format=application/json;odata.metadata=none"})
    void aCursorWritesTheSameBytesAsTheTable(String path) throws Exception {
        HttpResponse<String> cursor = send(path, DEMO);
        HttpResponse<String> table = send(path, DEMO, "Prefer", "odata.maxpagesize=1000");

        assertThat(cursor.statusCode()).as(cursor.body()).isEqualTo(200);
        assertThat(table.statusCode()).as(table.body()).isEqualTo(200);
        assertThat(cursor.body()).isEqualTo(table.body());
        assertThat(cursor.headers().firstValue("ETag")).isEqualTo(table.headers().firstValue("ETag"));
    }

    @Test
    void aResultOverTheHeapLimitsIsDelivered() throws Exception {
        HttpResponse<String> response = send("/odata/Long", DEMO);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("value").size()).isEqualTo(1000);
        JsonNode logged = lastExecution("Long");
        assertThat(logged.get("status").asString()).isEqualTo("OK");
        assertThat(logged.get("rowCount").asInt()).isEqualTo(1000);
        assertThat(logged.get("bytesSent").asLong()).isEqualTo(response.body().length());
        assertThat(logged.get("cacheStatus").asString()).isEqualTo("DISABLED");
    }

    @Test
    void etagsStillShortenARead() throws Exception {
        HttpResponse<String> first = send("/odata/Long", DEMO);

        HttpResponse<String> second = send("/odata/Long", DEMO, "If-None-Match", first.headers().firstValue("ETag").orElseThrow());

        assertThat(second.statusCode()).isEqualTo(304);
        assertThat(second.body()).isEmpty();
    }

    @Test
    void readsThatNeedTheSizeFirstKeepTheHeapLimit() throws Exception {
        assertThat(send("/odata/Long?$count=true", DEMO).statusCode()).isEqualTo(413);
        assertThat(send("/odata/Long", DEMO, "Prefer", "odata.maxpagesize=10").statusCode()).isEqualTo(413);
        assertThat(send("/odata/Long?$skiptoken=10", DEMO).statusCode()).isEqualTo(413);
        assertThat(send("/odata/Long?$top=1000", DEMO).statusCode()).isEqualTo(413);
    }

    @Test
    void theLimitOfTheQueryStillHolds() throws Exception {
        HttpResponse<String> refused = send("/odata/Capped", DEMO);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(413);
        assertThat(refused.body()).contains("500 rows");
        assertThat(lastExecution("Capped").get("status").asString()).isEqualTo("TOO_LARGE");
    }

    @Test
    void theByteLimitIsLoggedForACursor() throws Exception {
        HttpResponse<String> refused = send("/odata/Oversized", DEMO);

        assertThat(refused.statusCode()).as(refused.body().substring(0, Math.min(300, refused.body().length()))).isEqualTo(413);
        assertThat(refused.body()).contains("3 MB");
        assertThat(lastExecution("Oversized").get("status").asString()).isEqualTo("TOO_LARGE");
    }

    @Test
    void theByteLimitIsLoggedForACachedTable() throws Exception {
        HttpResponse<String> refused = send("/odata/OversizedCached", DEMO);

        assertThat(refused.statusCode()).as(refused.body().substring(0, Math.min(300, refused.body().length()))).isEqualTo(413);
        assertThat(lastExecution("OversizedCached").get("status").asString()).isEqualTo("TOO_LARGE");
    }

    @Test
    void aDatabaseErrorGivesAnErrorDocumentInsteadOfABrokenBody() throws Exception {
        HttpResponse<String> failed = send("/odata/Broken", DEMO);

        assertThat(failed.statusCode()).as(failed.body()).isEqualTo(500);
        assertThat(json.readTree(failed.body()).has("error")).isTrue();
        assertThat(failed.body()).doesNotContain("\"value\":[");
        assertThat(lastExecution("Broken").get("status").asString()).isEqualTo("DB_ERROR");
    }

    private JsonNode lastExecution(String query) throws Exception {
        HttpResponse<String> log = send("/api/v1/stats/executions?queryName=" + query + "&limit=1", ADMIN);
        assertThat(log.statusCode()).as(log.body()).isEqualTo(200);
        return json.readTree(log.body()).get(0);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", ADMIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String path, String authorization, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path.replace(";", "%3B")))
                .header("Authorization", authorization).GET();
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
