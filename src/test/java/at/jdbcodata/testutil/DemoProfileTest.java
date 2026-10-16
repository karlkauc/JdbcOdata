package at.jdbcodata.testutil;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Shared Spring context for integration tests: demo profile with in-memory metadata and demo databases.
 * All subclasses use identical properties so the context is started once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:meta-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret"
})
public abstract class DemoProfileTest {

    public static final String ADMIN = "admin";
    public static final String ADMIN_PASSWORD = "secret";

    @Value("${local.server.port}")
    protected int port;

    private final HttpClient client = HttpClient.newHttpClient();

    protected HttpResponse<String> get(String path, String user, String password) throws IOException, InterruptedException {
        return send("GET", path, null, user, password);
    }

    protected HttpResponse<String> send(String method, String path, String jsonBody, String user, String password)
            throws IOException, InterruptedException {
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
