package at.jdbcodata.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Separate context with a tiny limit, so the shared demo context is not affected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-rate;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-rate;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret-rate",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.rate-limit-per-minute=3"
})
class RateLimitIT {

    @Value("${local.server.port}")
    int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String user, String password, String ip) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET()
                .header("X-Forwarded-For", ip);
        if (user != null) {
            b.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes()));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void failedDatabaseLoginsAreRateLimitedPerIp() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(get("/odata/Sales?$top=1", "demo", "wrong" + i, "10.1.1.1").statusCode()).isEqualTo(401);
        }
        HttpResponse<String> blocked = get("/odata/Sales?$top=1", "demo", "demo", "10.1.1.1");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.headers().firstValue("Retry-After")).hasValue("60");
        assertThat(blocked.body()).contains("Too many failed logins");
        // the username "demo" is blocked as well, from any address
        assertThat(get("/odata/Sales?$top=1", "demo", "demo", "10.1.1.2").statusCode()).isEqualTo(429);
        // other users from other addresses still work
        assertThat(get("/actuator/health", null, null, "10.1.1.3").statusCode()).isEqualTo(200);
    }

    @Test
    void failedAdminLoginsAreRateLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            assertThat(get("/api/v1/queries", "admin", "bad" + i, "10.2.2.2").statusCode()).isEqualTo(401);
        }
        assertThat(get("/api/v1/queries", "admin", "secret-rate", "10.2.2.2").statusCode()).isEqualTo(429);
        // login form POSTs are covered by the same filter
        HttpRequest form = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/login"))
                .header("X-Forwarded-For", "10.2.2.2").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=admin&password=x")).build();
        assertThat(client.send(form, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(429);
    }
}
