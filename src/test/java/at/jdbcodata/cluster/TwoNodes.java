package at.jdbcodata.cluster;

import at.jdbcodata.JdbcOdataApplication;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** One running instance of the service. Several of them share a PostgreSQL metadata DB, as in production. */
final class TwoNodes {

    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    private TwoNodes() {
    }

    static final class Node implements AutoCloseable {

        final ConfigurableApplicationContext context;
        private final int port;

        Node(ConfigurableApplicationContext context) {
            this.context = context;
            this.port = Integer.parseInt(context.getEnvironment().getRequiredProperty("local.server.port"));
        }

        <T> T bean(Class<T> type) {
            return context.getBean(type);
        }

        HttpResponse<String> get(String path, String user, String password) throws IOException, InterruptedException {
            return send("GET", path, user, password);
        }

        HttpResponse<String> send(String method, String path, String user, String password) throws IOException, InterruptedException {
            String basic = Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
            return CLIENT.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .method(method, HttpRequest.BodyPublishers.noBody()).header("Authorization", "Basic " + basic).build(),
                    HttpResponse.BodyHandlers.ofString());
        }

        @Override
        public void close() {
            context.close();
        }
    }

    static Node start(PostgreSQLContainer postgres, String demoDatabase) throws IOException {
        Map<String, Object> properties = new HashMap<>();
        properties.put("server.port", 0);
        properties.put("spring.profiles.active", "demo");
        properties.put("spring.datasource.url", postgres.getJdbcUrl());
        properties.put("spring.datasource.username", postgres.getUsername());
        properties.put("spring.datasource.password", postgres.getPassword());
        properties.put("jdbcodata.data-dir", Files.createTempDirectory("jdbcodata-node").toString());
        properties.put("jdbcodata.demo.jdbc-url", "jdbc:h2:mem:" + demoDatabase + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        properties.put("jdbcodata.admin.user", "admin");
        properties.put("jdbcodata.admin.password", "secret-cluster");
        properties.put("jdbcodata.master-key", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=");
        properties.put("jdbcodata.stats.async", false);
        properties.put("jdbcodata.security.rate-limit-per-minute", 100000);
        properties.put("jdbcodata.security.allow-embedded-h2", true);
        properties.put("jdbcodata.cluster.poll-interval-ms", 200);
        // as arguments: default properties would lose against application.yml
        String[] args = properties.entrySet().stream().map(e -> "--" + e.getKey() + "=" + e.getValue()).toArray(String[]::new);
        return new Node(new SpringApplicationBuilder(JdbcOdataApplication.class).run(args));
    }
}
