package at.jdbcodata.datasource;

import static at.jdbcodata.testutil.TestData.datasource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class IdentityPoolManagerTest {

    private static final String URL = "jdbc:h2:mem:pooltest;DB_CLOSE_DELAY=-1";
    private final IdentityPoolManager pools = new IdentityPoolManager();

    @BeforeAll
    static void createDatabase() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "owner", "pw")) {
            c.createStatement().execute("CREATE USER IF NOT EXISTS other PASSWORD 'pw2' ADMIN");
        }
    }

    @AfterEach
    void tearDown() {
        pools.closeAll();
        assertThat(pools.activePoolCount()).isZero();
    }

    @Test
    void reusesPoolPerIdentity() throws Exception {
        Datasource ds = datasource("pool", URL);
        try (Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(c.isValid(1)).isTrue();
        }
        try (Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(c.isValid(1)).isTrue();
        }
        assertThat(pools.activePoolCount()).isEqualTo(1);
        try (Connection c = pools.connect(ds, "other", "pw2")) {
            assertThat(c.getMetaData().getUserName()).isEqualToIgnoringCase("other");
        }
        assertThat(pools.activePoolCount()).isEqualTo(2);
        pools.evictIdlePools();
        assertThat(pools.activePoolCount()).isEqualTo(2);
        pools.closePoolsFor(ds.id());
        assertThat(pools.activePoolCount()).isZero();
    }

    @Test
    void wrongPasswordDoesNotCreateAPool() {
        Datasource ds = datasource("pool", URL);
        assertThatThrownBy(() -> pools.connect(ds, "owner", "nope"))
                .isInstanceOf(DbAuthenticationException.class);
        assertThat(pools.activePoolCount()).isZero();
    }

    @Test
    void poolHoldsAsManyConnectionsAsTheDatasourceAllows() throws Exception {
        Datasource base = datasource("pool", URL);
        Datasource ds = new Datasource(base.id(), "pool", null, URL, null, null, 30, 10, 10, true, Instant.now(),
                Instant.now(), Map.of(), Map.of(), 3);
        try (Connection a = pools.connect(ds, "owner", "pw");
             Connection b = pools.connect(ds, "owner", "pw");
             Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(List.of(a, b, c)).allMatch(connection -> !isClosed(connection));
        }
        assertThat(pools.activePoolCount()).isEqualTo(1);
    }

    private static boolean isClosed(Connection connection) {
        try {
            return connection.isClosed();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void appliesSessionSettingsInSeparatePools() throws Exception {
        Datasource base = datasource("pool", URL);
        Datasource ds = new Datasource(base.id(), "pool", null, URL, null, null, 30, 10, 1, true, Instant.now(),
                Instant.now(), Map.of("TIME_ZONE", "+05:00"), Map.of());
        try (Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(offset(c)).isEqualTo(ZoneOffset.ofHours(5));
            c.rollback(); // the executor rolls back after each statement; the setting must survive
        }
        try (Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(offset(c)).isEqualTo(ZoneOffset.ofHours(5));
        }
        try (Connection c = pools.connect(ds, Map.of("TIME_ZONE", "-03:00"), "owner", "pw")) {
            assertThat(offset(c)).isEqualTo(ZoneOffset.ofHours(-3));
        }
        assertThat(pools.activePoolCount()).isEqualTo(2);
    }

    @Test
    void passesDriverProperties() throws Exception {
        Datasource base = datasource("pool", URL);
        Datasource ds = new Datasource(base.id(), "pool", null, URL, null, null, 30, 10, 1, true, Instant.now(),
                Instant.now(), Map.of(), Map.of("DEFAULT_NULL_ORDERING", "HIGH"));
        try (Connection c = pools.connect(ds, "owner", "pw");
             ResultSet rs = c.createStatement().executeQuery(
                     "SELECT SETTING_VALUE FROM INFORMATION_SCHEMA.SETTINGS WHERE SETTING_NAME = 'DEFAULT_NULL_ORDERING'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("HIGH");
        }
    }

    private static ZoneOffset offset(Connection c) throws Exception {
        try (ResultSet rs = c.createStatement().executeQuery("SELECT CURRENT_TIMESTAMP")) {
            rs.next();
            return rs.getObject(1, OffsetDateTime.class).getOffset();
        }
    }

    @Test
    void usesConfiguredDriverClass() throws Exception {
        Datasource ds = new Datasource(java.util.UUID.randomUUID(), "drv", null, URL, "org.h2.Driver", null, 30, 10, 1,
                true, null, null);
        try (Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(c.isValid(1)).isTrue();
        }
    }
}
