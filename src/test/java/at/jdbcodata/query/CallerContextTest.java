package at.jdbcodata.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.cache.CacheKey;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.datasource.CallerContext;
import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.identity.ExecutionIdentity;
import at.jdbcodata.testutil.TestData;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** The caller handed to the database session (H2 session variables), and kept out of runs that must not see it. */
class CallerContextTest {

    private static final String WHO = "SELECT CAST(@jdbcodata_caller AS VARCHAR(100)) AS who, "
            + "CAST(@jdbcodata_groups AS VARCHAR(200)) AS grps";

    private final IdentityPoolManager pools = new IdentityPoolManager();
    private final QueryExecutor executor = new QueryExecutor(pools, new ConcurrencyLimiter(), TestData.props(100, 20, ""));
    private final String url = "jdbc:h2:mem:caller-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1";
    /** One connection per pool: a value left in the session would reach the next run. */
    private final Datasource ds = new Datasource(UUID.randomUUID(), "callers", null, url, null, null, 30, 1000, 10, true,
            Instant.now(), Instant.now(), Map.of(), Map.of(), 1, true);

    @AfterEach
    void closePools() {
        pools.closeAll();
    }

    private static ExecutionIdentity as(String name, String... groups) {
        return new ExecutionIdentity(name, "sa", "", ExecutionMode.FIXED, new CallerContext(name, List.of(groups)));
    }

    private Object[] run(QueryDefinition q, ExecutionIdentity identity) {
        return executor.execute(q, ds, identity, Map.of(), 10, 10).rows().get(0);
    }

    @Test
    void eachRunCarriesItsOwnCaller() {
        QueryDefinition q = TestData.query("Who", WHO, PassCaller.INHERIT);
        assertThat(run(q, as("anna", "nord", "sued"))).containsExactly("anna", "nord,sued");
        assertThat(run(q, as("bernd"))).containsExactly("bernd", "");
        assertThat(run(q, as("anna", "nord"))).containsExactly("anna", "nord");
    }

    @Test
    void aQueryThatPassesNoCallerNeverSeesOneFromAnEarlierRun() {
        QueryDefinition on = TestData.query("On", WHO, PassCaller.INHERIT);
        QueryDefinition off = TestData.query("Off", WHO, PassCaller.OFF);
        assertThat(run(on, as("anna", "nord"))).containsExactly("anna", "nord");
        assertThat(run(off, as("anna", "nord"))).containsExactly(null, null);
        // a test run from the admin UI has no caller
        assertThat(run(on, new ExecutionIdentity("admin", "sa", "", ExecutionMode.FIXED))).containsExactly(null, null);
        assertThat(run(on, as("bernd"))).containsExactly("bernd", "");
    }

    @Test
    void theCursorPathPassesTheCallerToo() throws Exception {
        QueryDefinition q = TestData.query("Who", WHO, PassCaller.INHERIT);
        try (ResultCursor cursor = executor.open(q, ds, as("anna", "nord"), Map.of(), 10, 10)) {
            assertThat(cursor.next()).containsExactly("anna", "nord");
        }
    }

    @Test
    void theQueryDecidesUnlessItInherits() {
        Datasource off = TestData.datasource("off", url);
        assertThat(CallerContext.enabled(ds, TestData.query("Q", WHO, PassCaller.INHERIT))).isTrue();
        assertThat(CallerContext.enabled(ds, TestData.query("Q", WHO, PassCaller.OFF))).isFalse();
        assertThat(CallerContext.enabled(off, TestData.query("Q", WHO, PassCaller.INHERIT))).isFalse();
        assertThat(CallerContext.enabled(off, TestData.query("Q", WHO, PassCaller.ON))).isTrue();
        assertThat(QueryExecutor.callerFor(TestData.query("Q", WHO, PassCaller.OFF), ds, as("anna"))).isNull();
    }

    @Test
    void cachedResultsAreKeptApartPerCaller() {
        UUID id = UUID.randomUUID();
        CacheKey none = CacheKey.of(id, 1, "tech", Map.of(), Map.of());
        assertThat(CacheKey.of(id, 1, "tech", Map.of(), Map.of(), null)).isEqualTo(none);
        CacheKey anna = CacheKey.of(id, 1, "tech", Map.of(), Map.of(), new CallerContext("anna", List.of("nord")).cacheInput());
        CacheKey annaSued = CacheKey.of(id, 1, "tech", Map.of(), Map.of(), new CallerContext("anna", List.of("sued")).cacheInput());
        CacheKey bernd = CacheKey.of(id, 1, "tech", Map.of(), Map.of(), new CallerContext("bernd", List.of("nord")).cacheInput());
        assertThat(List.of(none, anna, annaSued, bernd)).doesNotHaveDuplicates();
    }

    @Test
    void onlyDatabasesThatCanReceiveTheCallerAreAccepted() {
        CallerContext.checkSupported("jdbc:postgresql://db/x");
        CallerContext.checkSupported("jdbc:oracle:thin:@db:1521/x");
        CallerContext.checkSupported("jdbc:mariadb://db/x");
        assertThatThrownBy(() -> CallerContext.checkSupported("jdbc:sqlserver://db"))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("PostgreSQL, Oracle");
    }

    @Test
    void oracleRefusesValuesOverItsLimitInsteadOfCuttingThem() {
        Datasource oracle = TestData.passingCaller("ora", "jdbc:oracle:thin:@nowhere:1521/x");
        CallerContext tooMany = new CallerContext("anna", List.of("a".repeat(40), "b".repeat(40)));
        // checked before any statement runs, so no connection is needed
        assertThatThrownBy(() -> tooMany.apply(null, oracle.jdbcUrl()))
                .isInstanceOf(DbExecutionException.class).hasMessageContaining("64 bytes");
    }
}
