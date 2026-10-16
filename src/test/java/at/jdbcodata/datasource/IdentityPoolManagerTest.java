package at.jdbcodata.datasource;

import static at.jdbcodata.testutil.TestData.datasource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
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
    void usesConfiguredDriverClass() throws Exception {
        Datasource ds = new Datasource(java.util.UUID.randomUUID(), "drv", null, URL, "org.h2.Driver", null, 30, 10, 1,
                true, null, null);
        try (Connection c = pools.connect(ds, "owner", "pw")) {
            assertThat(c.isValid(1)).isTrue();
        }
    }
}
