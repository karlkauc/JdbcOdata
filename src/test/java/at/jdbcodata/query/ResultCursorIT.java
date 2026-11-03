package at.jdbcodata.query;

import static at.jdbcodata.testutil.TestData.datasource;
import static at.jdbcodata.testutil.TestData.query;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.ExecutionIdentity;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** What the real drivers do with a read over many fetches, above all when it ends early. */
class ResultCursorIT {

    private static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");
    private static final GenericContainer<?> mariadb = new GenericContainer<>("mariadb:11.4").withExposedPorts(3306)
            .withEnv(Map.of("MARIADB_ROOT_PASSWORD", "root", "MARIADB_DATABASE", "cursor"))
            .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(3)));

    private static final JdbcOdataProperties props = at.jdbcodata.testutil.TestData.props(100, 20, "");
    private static final IdentityPoolManager pools = new IdentityPoolManager();
    private static final ConcurrencyLimiter limiter = new ConcurrencyLimiter();
    private static final QueryExecutor executor = new QueryExecutor(pools, limiter, props);

    /** SQL with {@code n} as row number and {@code %d} as upper bound. */
    record Database(String name, Datasource ds, ExecutionIdentity identity, String numbers, String stallAtRow2000, String failAtRow3000) {
        @Override
        public String toString() {
            return name;
        }
    }

    @BeforeAll
    static void start() {
        postgres.start();
        mariadb.start();
    }

    @AfterAll
    static void stop() {
        pools.closeAll();
        postgres.stop();
        mariadb.stop();
    }

    static Stream<Database> databases() {
        return Stream.of(
                new Database("PostgreSQL", datasource("pg", postgres.getJdbcUrl()),
                        new ExecutionIdentity(postgres.getUsername(), postgres.getUsername(), postgres.getPassword(), ExecutionMode.CALLER),
                        "SELECT generate_series(1, %d) AS n",
                        "SELECT n, pg_sleep(CASE WHEN n = 2000 THEN 60 ELSE 0 END)::text AS s FROM (SELECT generate_series(1, 5000) AS n) t",
                        "SELECT 1 / (3000 - n) AS x FROM (SELECT generate_series(1, 5000) AS n) t"),
                new Database("MariaDB", datasource("maria", "jdbc:mariadb://" + mariadb.getHost() + ":" + mariadb.getMappedPort(3306) + "/cursor"),
                        new ExecutionIdentity("root", "root", "root", ExecutionMode.CALLER),
                        "SELECT seq AS n FROM seq_1_to_%d",
                        "SELECT seq AS n, SLEEP(IF(seq = 2000, 60, 0)) AS s FROM seq_1_to_5000",
                        // strict mode turns a division by zero into an error only in writes, so a subquery fails here
                        "SELECT seq AS n, IF(seq < 3000, 1, (SELECT seq FROM seq_1_to_2)) AS x FROM seq_1_to_5000"));
    }

    private static ResultCursor open(Database db, String sql, int maxRows, int timeoutSec) {
        return executor.open(query("Cursor", sql, List.of(), List.of(), KeyMode.ROWID), db.ds(), db.identity(), Map.of(), maxRows, timeoutSec);
    }

    private static void assertSlotIsFree(Database db) {
        assertThat(limiter.available(db.ds())).isEqualTo(db.ds().maxConcurrent());
    }

    @ParameterizedTest
    @MethodSource("databases")
    void readsAllRowsToTheEnd(Database db) {
        try (ResultCursor cursor = open(db, db.numbers().formatted(5000), 5000, 30)) {
            assertThat(cursor.columns()).hasSize(1);
            long sum = 0;
            for (Object[] row = cursor.next(); row != null; row = cursor.next()) sum += ((Number) row[0]).longValue();
            assertThat(sum).isEqualTo(5000L * 5001 / 2);
            assertThat(cursor.rows()).isEqualTo(5000);
            assertThat(cursor.next()).as("after the end").isNull();
        }
        assertSlotIsFree(db);
    }

    @ParameterizedTest
    @MethodSource("databases")
    void aHugeResultStaysInTheDatabaseAndClosesAtOnce(Database db) {
        long start = System.nanoTime();
        try (ResultCursor cursor = open(db, db.numbers().formatted(2_000_000_000), 2_000_000_000 - 1, 60)) {
            assertThat(limiter.available(db.ds())).as("slot in use while reading").isEqualTo(db.ds().maxConcurrent() - 1);
            for (int i = 0; i < 5; i++) assertThat(cursor.next()).isNotNull();
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).as("buffered or drained 2 billion rows").isLessThan(Duration.ofSeconds(10));
        assertSlotIsFree(db);
        // the connection went back in working order
        try (ResultCursor again = open(db, db.numbers().formatted(3), 10, 30)) {
            assertThat(again.next()).isNotNull();
        }
    }

    @ParameterizedTest
    @MethodSource("databases")
    void moreRowsThanAllowedEndTheRead(Database db) {
        try (ResultCursor cursor = open(db, db.numbers().formatted(5000), 10, 30)) {
            assertThatThrownBy(() -> {
                while (cursor.next() != null) {
                    // read on
                }
            }).isInstanceOf(ResultTooLargeException.class);
            assertThat(cursor.rows()).isEqualTo(10);
        }
        assertSlotIsFree(db);
    }

    @ParameterizedTest
    @MethodSource("databases")
    void theTimeLimitCoversTheWholeRead(Database db) {
        long start = System.nanoTime();
        try (ResultCursor cursor = open(db, db.stallAtRow2000(), 5000, 2)) {
            assertThatThrownBy(() -> {
                while (cursor.next() != null) {
                    // read on
                }
            }).isInstanceOfSatisfying(DbExecutionException.class, e -> assertThat(e.isTimeout()).as(e.getMessage()).isTrue());
        }
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(20));
        assertSlotIsFree(db);
    }

    @ParameterizedTest
    @MethodSource("databases")
    void anErrorInTheMiddleOfTheResultEndsTheRead(Database db) {
        try (ResultCursor cursor = open(db, db.failAtRow3000(), 5000, 30)) {
            assertThatThrownBy(() -> {
                while (cursor.next() != null) {
                    // read on
                }
            }).isInstanceOf(DbExecutionException.class);
            assertThat(cursor.rows()).as("the rows before it arrived first").isGreaterThan(0);
        }
        assertSlotIsFree(db);
    }
}
