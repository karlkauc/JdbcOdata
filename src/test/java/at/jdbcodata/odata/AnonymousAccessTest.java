package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Base64;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code jdbcodata.odata.anonymous-user}: requests without an Authorization header run as the configured service
 * user (public demo feeds, OData explorers). Own Spring context because the property differs from
 * {@link at.jdbcodata.testutil.DemoProfileTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-anon;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-anon;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.cache.cleanup-interval-ms=3600000",
        "jdbcodata.security.rate-limit-per-minute=100000",
        "jdbcodata.odata.anonymous-user=public"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnonymousAccessTest {

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();
    private String userId;
    private String datasourceId;

    /** Service user "public" with stored demo-DB credentials, created through the admin API like an operator would. */
    @BeforeAll
    void createAnonymousServiceUser() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/users",
                "{\"username\":\"public\",\"displayName\":\"Anonymous demo access\",\"password\":\"unused-but-long-enough\"}",
                "admin", "secret");
        assertThat(created.statusCode()).isIn(200, 201);
        userId = json.readTree(created.body()).get("id").asString();
        HttpResponse<String> datasources = send("GET", "/api/v1/datasources", null, "admin", "secret");
        datasourceId = json.readTree(datasources.body()).get(0).get("id").asString();
    }

    @Test
    void withoutStoredCredentialsMetadataWorksButDataIsRefused() throws Exception {
        HttpResponse<String> metadata = send("GET", "/odata/$metadata", null, null, null);
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(metadata.body()).contains("EntitySet Name=\"Sales\"");

        HttpResponse<String> rows = send("GET", "/odata/Sales?$top=1", null, null, null);
        assertThat(rows.statusCode()).isEqualTo(401);
        assertThat(rows.headers().firstValue("WWW-Authenticate")).isPresent();
    }

    @Test
    void withStoredCredentialsAnonymousRequestsReturnData() throws Exception {
        HttpResponse<String> stored = send("PUT", "/api/v1/users/" + userId + "/credentials/" + datasourceId,
                "{\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}", "admin", "secret");
        assertThat(stored.statusCode()).isIn(200, 201);
        try {
            HttpResponse<String> service = send("GET", "/odata/", null, null, null);
            assertThat(service.statusCode()).isEqualTo(200);
            assertThat(service.body()).contains("\"name\":\"Sales\"");

            HttpResponse<String> rows = send("GET", "/odata/Sales?$top=2&$count=true", null, null, null);
            assertThat(rows.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(rows.body());
            assertThat(body.get("value").size()).isEqualTo(2);
            assertThat(body.get("@odata.count").asInt()).isEqualTo(24);

            // The execution log records the anonymous caller, not the service user's name.
            HttpResponse<String> log = send("GET", "/api/v1/stats/executions?caller=anonymous&limit=5", null, "admin", "secret");
            if (log.statusCode() == 200) {
                assertThat(log.body()).contains("anonymous");
            }
        } finally {
            send("DELETE", "/api/v1/users/" + userId + "/credentials/" + datasourceId, null, "admin", "secret");
        }
    }

    @Test
    void credentialsStillCount() throws Exception {
        HttpResponse<String> wrong = send("GET", "/odata/Sales?$top=1", null, "demo", "wrong");
        assertThat(wrong.statusCode()).isEqualTo(401);
        HttpResponse<String> right = send("GET", "/odata/Sales?$top=1", null, "demo", "demo");
        assertThat(right.statusCode()).isEqualTo(200);
    }

    @Test
    void anonymousCallersGetNoDatabaseErrorText() throws Exception {
        String demoDb = "jdbc:h2:mem:demo-anon;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection c = DriverManager.getConnection(demoDb, "demo", "demo"); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE vanishing (id INT)");
            HttpResponse<String> q = send("POST", "/api/v1/queries", """
                    {"name":"Failing","datasourceId":"%s","sql":"SELECT id FROM vanishing","executionMode":"CALLER"}"""
                    .formatted(datasourceId), "admin", "secret");
            assertThat(q.statusCode()).as(q.body()).isEqualTo(201);
            String qId = json.readTree(q.body()).get("id").asString();
            send("PUT", "/api/v1/users/" + userId + "/credentials/" + datasourceId,
                    "{\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}", "admin", "secret");
            try {
                assertThat(send("POST", "/api/v1/queries/" + qId + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", "admin", "secret").statusCode()).isEqualTo(200);
                st.execute("DROP TABLE vanishing");

                HttpResponse<String> anonymous = send("GET", "/odata/Failing", null, null, null);
                assertThat(anonymous.statusCode()).as(anonymous.body()).isEqualTo(500);
                assertThat(anonymous.body()).contains("(ref ").doesNotContain("SQLState").doesNotContain("vanishing");

                HttpResponse<String> loggedIn = send("GET", "/odata/Failing", null, "demo", "demo");
                assertThat(loggedIn.body()).contains("SQLState");
            } finally {
                send("DELETE", "/api/v1/queries/" + qId, null, "admin", "secret");
                send("DELETE", "/api/v1/users/" + userId + "/credentials/" + datasourceId, null, "admin", "secret");
            }
        }
    }

    @Test
    void allAuthenticatedGrantDoesNotIncludeAnonymousCallers() throws Exception {
        HttpResponse<String> fixed = send("POST", "/api/v1/fixed-credentials",
                "{\"name\":\"tech_anon\",\"datasourceId\":\"%s\",\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}"
                        .formatted(datasourceId), "admin", "secret");
        assertThat(fixed.statusCode()).as(fixed.body()).isEqualTo(201);
        String fixedId = json.readTree(fixed.body()).get("id").asString();
        HttpResponse<String> q = send("POST", "/api/v1/queries", """
                {"name":"FixedForAll","datasourceId":"%s","sql":"SELECT id FROM sales","executionMode":"FIXED","fixedCredentialId":"%s"}"""
                .formatted(datasourceId, fixedId), "admin", "secret");
        assertThat(q.statusCode()).as(q.body()).isEqualTo(201);
        String qId = json.readTree(q.body()).get("id").asString();
        try {
            assertThat(send("POST", "/api/v1/queries/" + qId + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}", "admin", "secret").statusCode()).isEqualTo(200);
            assertThat(send("PUT", "/api/v1/queries/" + qId + "/grants",
                    "{\"grants\":[{\"principalType\":\"ALL_AUTHENTICATED\"}]}", "admin", "secret").statusCode()).isEqualTo(200);

            assertThat(send("GET", "/odata/", null, null, null).body()).doesNotContain("FixedForAll");
            assertThat(send("GET", "/odata/FixedForAll?$top=1", null, null, null).statusCode()).isEqualTo(404);
            HttpResponse<String> loggedIn = send("GET", "/odata/FixedForAll?$top=1", null, "demo", "demo");
            assertThat(loggedIn.statusCode()).as(loggedIn.body()).isEqualTo(200);
        } finally {
            send("DELETE", "/api/v1/queries/" + qId, null, "admin", "secret");
            send("DELETE", "/api/v1/fixed-credentials/" + fixedId, null, "admin", "secret");
        }
    }

    private HttpResponse<String> send(String method, String path, String jsonBody, String user, String password)
            throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (jsonBody == null) {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            b.method(method, HttpRequest.BodyPublishers.ofString(jsonBody)).header("Content-Type", "application/json");
        }
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes()));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
