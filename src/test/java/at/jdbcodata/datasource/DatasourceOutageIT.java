package at.jdbcodata.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** What the drivers really report for a dead database, a wrong password and a full pool. */
class DatasourceOutageIT {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    private final DatasourceCircuitBreaker breaker = new DatasourceCircuitBreaker(5, 120, 1);
    private final IdentityPoolManager pools = new IdentityPoolManager(new JdbcUrlPolicy(List.of(), false), breaker);

    @BeforeAll
    static void start() {
        postgres.start();
    }

    @AfterAll
    static void stop() {
        postgres.stop();
    }

    @AfterEach
    void closePools() {
        pools.closeAll();
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:postgresql://localhost:%d/x", "jdbc:mariadb://localhost:%d/x"})
    void refusedConnectionsOpenTheBreaker(String url) throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        Datasource ds = datasource(url.formatted(closedPort), 2);

        for (int i = 0; i < 5; i++) {
            assertThatThrownBy(() -> pools.connect(ds, "user", "password")).isInstanceOf(DbExecutionException.class)
                    .isNotInstanceOf(DatasourceUnavailableException.class);
        }

        assertThat(breaker.isOpen(ds)).isTrue();
        assertThatThrownBy(() -> pools.connect(ds, "user", "password")).isInstanceOf(DatasourceUnavailableException.class);
    }

    @Test
    void wrongPasswordsDoNotOpenTheBreaker() {
        Datasource ds = datasource(postgres.getJdbcUrl(), 2);
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> pools.connect(ds, postgres.getUsername(), "wrong"))
                    .isInstanceOf(DbAuthenticationException.class);
        }
        assertThat(breaker.isOpen(ds)).isFalse();
    }

    @Test
    void aFullPoolDoesNotCountAsOutage() throws Exception {
        Datasource ds = datasource(postgres.getJdbcUrl(), 1);
        try (Connection busy = pools.connect(ds, postgres.getUsername(), postgres.getPassword())) {
            assertThat(busy.isValid(1)).isTrue();
            Throwable timeout = null;
            try (Connection none = pools.connect(ds, postgres.getUsername(), postgres.getPassword())) {
                assertThat(none).isNull();
            } catch (java.sql.SQLException e) {
                timeout = e;
            }
            assertThat(timeout).isInstanceOf(java.sql.SQLTransientConnectionException.class);
            assertThat(DbErrorClassifier.isConnectionFailure(timeout)).isFalse();
        }
    }

    /** The database stops answering: requests wait for the connect timeout, then fail at once, then it recovers. */
    @Test
    void aFrozenDatabaseFailsFastAndRecoversByItself() throws Exception {
        Datasource ds = datasource(postgres.getJdbcUrl(), 5);
        try (Connection warm = pools.connect(ds, postgres.getUsername(), postgres.getPassword())) {
            assertThat(warm.isValid(1)).isTrue();
        }
        var docker = postgres.getDockerClient();
        docker.pauseContainerCmd(postgres.getContainerId()).exec();
        try {
            Thread.sleep(600); // Hikari hands out a connection used in the last 500 ms without checking it
            try (ExecutorService threads = Executors.newFixedThreadPool(5)) {
                List<Future<?>> waiting = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    waiting.add(threads.submit(() -> {
                        assertThatThrownBy(() -> pools.connect(ds, postgres.getUsername(), postgres.getPassword()))
                                .isInstanceOf(java.sql.SQLException.class);
                    }));
                }
                for (Future<?> f : waiting) f.get();
            }
            assertThat(breaker.isOpen(ds)).isTrue();

            long start = System.nanoTime();
            assertThatThrownBy(() -> pools.connect(ds, postgres.getUsername(), postgres.getPassword()))
                    .isInstanceOf(DatasourceUnavailableException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(100));
        } finally {
            docker.unpauseContainerCmd(postgres.getContainerId()).exec();
        }

        Thread.sleep(1_100);
        try (Connection back = pools.connect(ds, postgres.getUsername(), postgres.getPassword())) {
            assertThat(back.isValid(1)).isTrue();
        }
        assertThat(breaker.isOpen(ds)).isFalse();
    }

    private static Datasource datasource(String url, int poolSize) {
        return new Datasource(UUID.randomUUID(), "outage", null, url, null, null, 30, 10, 10, true, Instant.now(), Instant.now(),
                Map.of(), Map.of(), poolSize);
    }
}
