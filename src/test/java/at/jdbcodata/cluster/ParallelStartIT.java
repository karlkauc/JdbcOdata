package at.jdbcodata.cluster;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.cluster.TwoNodes.Node;
import at.jdbcodata.datasource.DatasourceService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Rollouts and restarts start several instances at once on an empty database: only one may create the first data. */
@Testcontainers(disabledWithoutDocker = true)
class ParallelStartIT {

    private static final int ROUNDS = 3;
    private static final int NODES = 3;

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    /** All nodes share this JVM, unlike in production, and Spring Boot's logging setup is not made for parallel starts. */
    @BeforeAll
    static void keepLoggingSetup() {
        System.setProperty(LoggingSystem.SYSTEM_PROPERTY, LoggingSystem.NONE);
    }

    @AfterAll
    static void restoreLoggingSetup() {
        System.clearProperty(LoggingSystem.SYSTEM_PROPERTY);
    }

    @Test
    void nodesStartingAtTheSameTimeAllComeUp() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            String database = "parallel" + round;
            try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                 Statement st = c.createStatement()) {
                st.execute("CREATE DATABASE " + database);
            }
            String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + database);
            List<Node> nodes = startTogether(url, "parallel-demo-" + round);
            try {
                assertThat(nodes).hasSize(NODES);
                assertThat(nodes.getFirst().bean(AdminUserService.class).count()).isEqualTo(1);
                assertThat(nodes.getFirst().bean(DatasourceService.class).findAll()).hasSize(1);
            } finally {
                nodes.forEach(Node::close);
            }
        }
    }

    /** Waits for every node, so that a failed start does not leave others running. */
    private static List<Node> startTogether(String url, String demoDatabase) throws InterruptedException {
        List<Node> started = new ArrayList<>();
        List<Throwable> failures = new ArrayList<>();
        try (ExecutorService executor = Executors.newFixedThreadPool(NODES)) {
            List<Future<Node>> starts = new ArrayList<>();
            for (int i = 0; i < NODES; i++) {
                starts.add(executor.submit(() -> TwoNodes.start(url, postgres, demoDatabase)));
            }
            for (Future<Node> start : starts) {
                try {
                    started.add(start.get());
                } catch (ExecutionException e) {
                    failures.add(e.getCause());
                }
            }
        }
        if (!failures.isEmpty()) {
            started.forEach(Node::close);
            throw new AssertionError(failures.size() + " of " + NODES + " nodes did not start", failures.getFirst());
        }
        return started;
    }
}
