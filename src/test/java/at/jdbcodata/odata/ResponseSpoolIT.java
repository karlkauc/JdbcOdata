package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/** Bodies over 1 MB on their way through the temporary file. Own Spring context for the limit of 3 MB. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-spool;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-spool;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=100000",
        "jdbcodata.odata.max-rows=1000000",
        "jdbcodata.odata.response-rows-budget=2000000",
        "jdbcodata.odata.max-response-mb=3"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseSpoolIT {

    private static final String DEMO = basic("demo", "demo");
    private static final String ADMIN = basic("admin", "secret");
    /** About 2 MB of JSON. */
    private static final int ROWS = 90_000;

    @Value("${local.server.port}")
    private int port;
    @Autowired
    ResponseSpools spools;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void createFeeds() throws Exception {
        String datasource = json.readTree(send("GET", "/api/v1/datasources", null, ADMIN).body()).get(0).get("id").asString();
        feed("Spooled", "SELECT * FROM SYSTEM_RANGE(1, " + ROWS + ")", datasource);
        feed("Oversized", "SELECT REPEAT('x', 200) AS payload FROM SYSTEM_RANGE(1, 50000)", datasource); // below the row limit
    }

    private void feed(String name, String sql, String datasource) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"%s","datasourceId":"%s","cacheEnabled":false,"sql":"%s"}"""
                .formatted(name, datasource, sql), ADMIN);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        HttpResponse<String> snapshot = send("POST", "/api/v1/queries/" + json.readTree(created.body()).get("id").asString()
                + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", ADMIN);
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
    }

    @AfterEach
    void nothingStaysBehind() throws Exception {
        // the client has the response a moment before the server releases it
        for (int i = 0; i < 100 && spools.diskBytes() > 0; i++) Thread.sleep(50);
        assertThat(spools.diskBytes()).isZero();
        try (Stream<Path> files = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            assertThat(files.filter(f -> f.getFileName().toString().startsWith("jdbcodata-spool-"))).isEmpty();
        }
    }

    @Test
    void largeBodyArrivesCompleteWithItsETag() throws Exception {
        HttpResponse<String> full = send("GET", "/odata/Spooled?$count=true", null, DEMO);

        assertThat(full.statusCode()).as(full.body().substring(0, Math.min(300, full.body().length()))).isEqualTo(200);
        assertThat(full.body().length()).isGreaterThan(ResponseSpools.MEMORY_BYTES);
        var body = json.readTree(full.body());
        assertThat(body.get("value").size()).isEqualTo(ROWS);
        assertThat(body.get("@odata.count").asInt()).isEqualTo(ROWS);
        String etag = full.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).isEqualTo(ResponseETag.of(full.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(send("GET", "/odata/Spooled?$count=true", null, DEMO, "If-None-Match", etag).statusCode()).isEqualTo(304);
    }

    @Test
    void bodyAboveTheLimitGets413() throws Exception {
        HttpResponse<String> refused = send("GET", "/odata/Oversized", null, DEMO);

        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(413);
        assertThat(refused.body()).contains("3 MB");
    }

    @Test
    void asyncResultSurvivesTheEndOfTheRequest() throws Exception {
        HttpResponse<String> accepted = send("GET", "/odata/Spooled", null, DEMO, "Prefer", "respond-async");
        assertThat(accepted.statusCode()).isEqualTo(202);
        String monitor = URI.create(accepted.headers().firstValue("Location").orElseThrow()).getPath();

        HttpResponse<String> done = send("GET", monitor, null, DEMO);
        for (int i = 0; i < 200 && done.statusCode() == 202; i++) {
            Thread.sleep(50);
            done = send("GET", monitor, null, DEMO);
        }

        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(done.body()).contains("HTTP/1.1 200").contains("\"value\":[");
        assertThat(done.body().length()).isGreaterThan(ResponseSpools.MEMORY_BYTES);
    }

    @Test
    void batchPartIsReadBeforeTheSpoolGoes() throws Exception {
        String batch = "--b\r\nContent-Type: application/http\r\nContent-Transfer-Encoding: binary\r\n\r\n"
                + "GET Spooled HTTP/1.1\r\nAccept: application/json\r\n\r\n\r\n--b--\r\n";
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/$batch"))
                .header("Authorization", DEMO).header("Content-Type", "multipart/mixed;boundary=b")
                .POST(HttpRequest.BodyPublishers.ofString(batch)).build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("HTTP/1.1 200").contains("\"value\":[");
        assertThat(response.body().length()).isGreaterThan(ResponseSpools.MEMORY_BYTES);
    }

    private static String basic(String user, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    private HttpResponse<String> send(String method, String path, String body, String authorization, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", authorization).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
