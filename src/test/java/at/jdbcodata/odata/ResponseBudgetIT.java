package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.datasource.ResponseBudget;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.json.JsonMapper;

/** Cache hits never reach the database limiter, so the budget has to stop them. Own Spring context for the limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-budget;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-budget;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=100000",
        "jdbcodata.odata.response-rows-budget=3000",
        "jdbcodata.odata.response-wait-ms=100"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseBudgetIT {

    private static final String BASIC_ADMIN = basic("admin", "secret");
    private static final String BASIC_DEMO = basic("demo", "demo");

    @Value("${local.server.port}")
    private int port;
    @Autowired
    ResponseBudget budget;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

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

    @BeforeAll
    void createFeedWithCache() throws Exception {
        var datasources = json.readTree(send("GET", "/api/v1/datasources", null, BASIC_ADMIN).body());
        String datasource = datasources.get(0).get("id").asString();
        HttpResponse<String> created = send("POST", "/api/v1/queries", """
                {"name":"Big","datasourceId":"%s","cacheEnabled":true,"cacheTtlSec":3600,
                 "sql":"SELECT * FROM SYSTEM_RANGE(1, 2000)"}""".formatted(datasource), BASIC_ADMIN);
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        String id = json.readTree(created.body()).get("id").asString();
        HttpResponse<String> snapshot = send("POST", "/api/v1/queries/" + id + "/snapshot",
                "{\"username\":\"demo\",\"password\":\"demo\"}", BASIC_ADMIN);
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
    }

    @Test
    void cacheHitWaitsForTheBudgetAndGetsA503WithoutRoom() throws Exception {
        assertThat(send("GET", "/odata/Big", null, BASIC_DEMO).statusCode()).isEqualTo(200);
        HttpResponse<String> hit = send("GET", "/odata/Big", null, BASIC_DEMO);
        assertThat(hit.headers().firstValue("X-Cache")).hasValue("HIT");

        try (ResponseBudget.Reservation full = budget.reserve(3_000)) {
            HttpResponse<String> busy = send("GET", "/odata/Big", null, BASIC_DEMO);
            assertThat(busy.statusCode()).as(busy.body()).isEqualTo(503);
            assertThat(busy.headers().firstValue("Retry-After")).isPresent();
            assertThat(busy.body()).contains("large responses");
        }
        assertThat(send("GET", "/odata/Big", null, BASIC_DEMO).statusCode()).isEqualTo(200);
        assertThat(budget.reservedRows()).isZero();
    }

    @Test
    void smallFeedsAnswerWhileTheBudgetIsUsedUp() throws Exception {
        try (ResponseBudget.Reservation full = budget.reserve(3_000)) {
            assertThat(send("GET", "/odata/Sales", null, BASIC_DEMO).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void budgetComesBackAfterBatchAsyncAndErrors() throws Exception {
        assertThat(send("GET", "/odata/Big?$filter=nosuch%20eq%201", null, BASIC_DEMO).statusCode()).isEqualTo(400);
        assertBudgetReturned();

        HttpResponse<String> async = send("GET", "/odata/Big", null, BASIC_DEMO, "Prefer", "respond-async");
        assertThat(async.statusCode()).isEqualTo(202);
        String monitor = URI.create(async.headers().firstValue("Location").orElseThrow()).getPath();
        for (int i = 0; i < 100 && send("GET", monitor, null, BASIC_DEMO).statusCode() == 202; i++) {
            Thread.sleep(50);
        }
        assertBudgetReturned();

        String batch = """
                --b\r
                Content-Type: application/http\r
                Content-Transfer-Encoding: binary\r
                \r
                GET Big HTTP/1.1\r
                \r
                \r
                --b--\r
                """;
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/odata/$batch"))
                .header("Authorization", BASIC_DEMO).header("Content-Type", "multipart/mixed;boundary=b")
                .POST(HttpRequest.BodyPublishers.ofString(batch)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertBudgetReturned();
    }

    /** The budget comes back right after the last byte went out, so the client can be a moment ahead of it. */
    private void assertBudgetReturned() throws InterruptedException {
        for (int i = 0; i < 100 && budget.reservedRows() != 0; i++) {
            Thread.sleep(20);
        }
        assertThat(budget.reservedRows()).isZero();
    }
}
