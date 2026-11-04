package at.jdbcodata.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

/** Traces of OData reads: one per request, with database spans below, and no data in them. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-tracing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-tracing;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=100000",
        "management.tracing.export.enabled=true",
        "management.tracing.sampling.probability=1.0",
        "management.opentelemetry.tracing.export.schedule-delay=20ms"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TracingIT {

    private static final String DEMO = "Basic " + Base64.getEncoder().encodeToString("demo:demo".getBytes());
    private static final String ADMIN = "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes());
    private static final String SQL = "SELECT ROWNUM() AS n, 'Geheimwert' AS secret FROM SYSTEM_RANGE(1, 50)";
    private static final AttributeKey<String> HTTP_URL = AttributeKey.stringKey("http.url");
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @TestConfiguration
    static class Exporter {
        @Bean
        InMemorySpanExporter inMemorySpanExporter() {
            return InMemorySpanExporter.create();
        }
    }

    @Value("${local.server.port}")
    private int port;
    @Autowired
    InMemorySpanExporter exporter;
    @Autowired
    ApplicationContext context;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeAll
    void createFeed() throws Exception {
        String datasource = json.readTree(send("/api/v1/datasources", ADMIN).body()).get(0).get("id").asString();
        HttpResponse<String> created = post("/api/v1/queries", """
                {"name":"Traced","datasourceId":"%s","cacheEnabled":false,"sql":"%s"}""".formatted(datasource, SQL));
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        HttpResponse<String> snapshot = post("/api/v1/queries/" + json.readTree(created.body()).get("id").asString()
                + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}");
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
    }

    @BeforeEach
    void forgetEarlierSpans() {
        exporter.reset();
    }

    @Test
    void aReadIsOneTraceWithItsDatabaseSpan() throws Exception {
        HttpResponse<String> response = send("/odata/Traced", DEMO, "traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);

        SpanData db = await(s -> s.getName().equals("cursor Traced"));
        SpanData server = await(s -> s.getName().equals("http get /odata/Traced"));
        assertThat(db.getTraceId()).as("continues the caller's trace").isEqualTo(TRACE_ID);
        assertThat(server.getTraceId()).isEqualTo(TRACE_ID);
        assertThat(db.getKind()).isEqualTo(SpanKind.CLIENT);
        assertThat(db.getAttributes().get(Tracing.DB_SYSTEM)).isEqualTo("h2");
        assertThat(db.getAttributes().get(Tracing.QUERY)).isEqualTo("Traced");
        assertThat(db.getAttributes().get(Tracing.DB_ROWS)).isEqualTo(50L);
        assertThat(db.getEvents()).extracting(e -> e.getName()).containsExactly("connected", "executed");
        assertThat(server.getAttributes().get(Tracing.QUERY)).isEqualTo("Traced");
        assertThat(server.getAttributes().get(AttributeKey.stringKey("jdbcodata.rows"))).isEqualTo("50");
        assertThat(server.getAttributes().get(AttributeKey.stringKey("jdbcodata.cache"))).isEqualTo("DISABLED");
        assertThat(server.getAttributes().get(AttributeKey.stringKey("jdbcodata.status"))).isEqualTo("OK");
        assertThat(isBelow(db, server)).as("database span below the request").isTrue();
        assertThat(exporter.getFinishedSpanItems()).extracting(SpanData::getName)
                .noneMatch(name -> name.startsWith("security filterchain") || name.startsWith("task "));
    }

    @Test
    void healthProbesLeaveNoTraces() throws Exception {
        assertThat(send("/actuator/health", null).statusCode()).isEqualTo(200);
        assertThat(send("/odata/Traced", DEMO).statusCode()).isEqualTo(200);
        await(s -> s.getName().equals("http get /odata/Traced"));
        Thread.sleep(200);

        // every trace belongs to a read; spans of the previous test may still arrive after the reset
        List<SpanData> spans = exporter.getFinishedSpanItems();
        List<String> reads = spans.stream().filter(s -> s.getName().startsWith("http get /odata/")).map(SpanData::getTraceId).toList();
        assertThat(spans).as("only reads").extracting(SpanData::getTraceId).allMatch(reads::contains);
    }

    /** Tracing on does not push metrics: without a receiver that is a stack trace in the log every minute. */
    @Test
    void metricsAreNotPushedOverOtlp() {
        assertThat(context.getBeanNamesForType(OtlpMeterRegistry.class)).isEmpty();
    }

    @Test
    void unknownPathsDoNotBecomeSpanNames() throws Exception {
        assertThat(send("/odata/Nonsense4711", DEMO).statusCode()).isEqualTo(404);

        SpanData server = await(s -> s.getKind() == SpanKind.SERVER && "/odata/Nonsense4711".equals(s.getAttributes().get(HTTP_URL)));
        assertThat(server.getName()).isEqualTo("http get");
    }

    @Test
    void spansHoldNoSqlValuesOrUserNames() throws Exception {
        String filter = URLEncoder.encode("secret eq 'Geheimwert' and n gt 7", StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> filtered = send("/odata/Traced?$filter=" + filter, DEMO);
        assertThat(filtered.statusCode()).as(filtered.body()).isEqualTo(200);
        assertThat(send("/odata/Traced(7)", DEMO).statusCode()).isIn(200, 400, 404);
        assertThat(send("/odata/Traced?$filter=nope eq 1", DEMO).statusCode()).isEqualTo(400);
        await(s -> s.getName().equals("query Traced"));
        Thread.sleep(300);

        List<SpanData> spans = exporter.getFinishedSpanItems();
        for (SpanData span : spans) {
            // the demo datasource shares its name with the demo user
            String text = (span.getName() + " " + span.getAttributes() + " " + span.getEvents()).replace("jdbcodata.datasource=\"demo\"", "");
            assertThat(text).as(span.getName())
                    .doesNotContain("Geheimwert", "SYSTEM_RANGE", "ROWNUM", "(7)", "eq ", "$filter", "admin", "secret eq")
                    .doesNotContainPattern("\\bdemo\\b");
            span.getAttributes().forEach((key, value) -> assertThat(key.getKey()).doesNotContain("user", "statement", "query.text"));
        }
    }

    @Test
    void aDatabaseErrorShowsOnlyItsKind() throws Exception {
        String datasource = json.readTree(send("/api/v1/datasources", ADMIN).body()).get(0).get("id").asString();
        // fails after the rows of the snapshot; the message of H2 quotes the SQL
        HttpResponse<String> created = post("/api/v1/queries", """
                {"name":"TracedBroken","datasourceId":"%s","cacheEnabled":false,"sql":"SELECT 1 / (ROWNUM() - 1000) AS r FROM SYSTEM_RANGE(1, 1000)"}"""
                .formatted(datasource));
        assertThat(created.statusCode()).as(created.body()).isIn(200, 201);
        HttpResponse<String> snapshot = post("/api/v1/queries/" + json.readTree(created.body()).get("id").asString()
                + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}");
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
        exporter.reset();

        assertThat(send("/odata/TracedBroken", DEMO).statusCode()).isGreaterThanOrEqualTo(400);

        SpanData db = await(s -> s.getName().equals("cursor TracedBroken"));
        assertThat(db.getStatus().getStatusCode()).as(exporter.getFinishedSpanItems().toString()).isEqualTo(StatusCode.ERROR);
        assertThat(db.getAttributes().get(Tracing.ERROR_TYPE)).isNotBlank();
        assertThat(db.getEvents()).noneMatch(e -> e.getName().equals("exception"));
        assertThat(db.toString()).doesNotContain("ROWNUM", "Division");
    }

    @Test
    void anAsynchronousJobStaysInTheTraceOfItsRequest() throws Exception {
        HttpResponse<String> accepted = send("/odata/Traced", DEMO, "Prefer", "respond-async",
                "traceparent", "00-" + TRACE_ID + "-00f067aa0ba902b7-01");
        assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);

        SpanData db = await(s -> s.getKind() == SpanKind.CLIENT && s.getName().endsWith(" Traced"));
        assertThat(db.getTraceId()).isEqualTo(TRACE_ID);
    }

    private boolean isBelow(SpanData child, SpanData ancestor) {
        List<SpanData> all = exporter.getFinishedSpanItems();
        SpanData current = child;
        for (int depth = 0; depth < 20 && current != null; depth++) {
            if (current.getParentSpanId().equals(ancestor.getSpanId())) return true;
            String parent = current.getParentSpanId();
            current = all.stream().filter(s -> s.getSpanId().equals(parent)).findFirst().orElse(null);
        }
        return false;
    }

    private SpanData await(Predicate<SpanData> match) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            for (SpanData span : exporter.getFinishedSpanItems()) if (match.test(span)) return span;
            Thread.sleep(20);
        }
        throw new AssertionError("no such span in " + exporter.getFinishedSpanItems().stream()
                .map(s -> s.getName() + s.getAttributes()).toList());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", ADMIN).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String path, String authorization, String... headers) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path.replace(" ", "%20")))
                .GET();
        if (authorization != null) request.header("Authorization", authorization);
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
