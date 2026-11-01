package at.jdbcodata.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/** Own Spring context because the property differs from {@link at.jdbcodata.testutil.DemoProfileTest}. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-token;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-token;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.security.metrics-token=" + MetricsTokenTest.TOKEN
})
class MetricsTokenTest {

    static final String TOKEN = "0123456789abcdef0123456789abcdef";

    @Value("${local.server.port}")
    private int port;

    private final HttpClient client = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void tokenOpensTheMetricsWithoutASession() throws Exception {
        HttpResponse<String> metrics = get(MetricsTokenMatcher.PATH, "Bearer " + TOKEN);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jdbcodata_pools ");
        assertThat(metrics.headers().firstValue("Set-Cookie")).isEmpty();
    }

    @Test
    void wrongOrMissingTokenIsRefused() throws Exception {
        assertThat(get(MetricsTokenMatcher.PATH, "Bearer " + TOKEN + "x").statusCode()).isIn(302, 401);
        assertThat(get(MetricsTokenMatcher.PATH, "Bearer ").statusCode()).isIn(302, 401);
        assertThat(get(MetricsTokenMatcher.PATH, TOKEN).statusCode()).isIn(302, 401);
        assertThat(get(MetricsTokenMatcher.PATH, null).statusCode()).isIn(302, 401);
    }

    @Test
    void tokenOpensNothingElse() throws Exception {
        assertThat(get("/api/v1/audit", "Bearer " + TOKEN).statusCode()).isEqualTo(401);
        assertThat(get("/admin", "Bearer " + TOKEN).statusCode()).isIn(302, 401);
        assertThat(get("/actuator/prometheus/", "Bearer " + TOKEN).statusCode()).isIn(302, 401, 404);
    }

    @Test
    void adminLoginStillWorks() throws Exception {
        String basic = "Basic " + Base64.getEncoder().encodeToString("admin:secret".getBytes());
        assertThat(get(MetricsTokenMatcher.PATH, basic).statusCode()).isEqualTo(200);
    }

    @Test
    void shortTokenStopsTheStart() {
        assertThatThrownBy(() -> new MetricsTokenMatcher("too-short")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32");
    }
}
