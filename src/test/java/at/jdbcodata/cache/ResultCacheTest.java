package at.jdbcodata.cache;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.ResultColumn;
import at.jdbcodata.query.ResultTable;
import at.jdbcodata.query.TypeMapper;
import at.jdbcodata.testutil.DemoProfileTest;
import java.sql.Types;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ResultCacheTest extends DemoProfileTest {

    @Autowired
    ResultCache cache;
    @Autowired
    QueryService queryService;
    @Autowired
    CacheEntryRepository repository;

    private static ResultTable table(int rows) {
        ResultColumn c = new ResultColumn("v", Types.INTEGER, "INTEGER", 10, 0, true, TypeMapper.map(Types.INTEGER, "INTEGER", 10, 0));
        List<Object[]> data = new java.util.ArrayList<>();
        for (int i = 0; i < rows; i++) data.add(new Object[] {i});
        return new ResultTable(List.of(c), data, false);
    }

    @Test
    void loadsOncePerKeyAndServesHits() {
        QueryDefinition sales = queryService.findEnabledByName("Sales").orElseThrow();
        UUID q = sales.id();
        CacheKey key = CacheKey.of(q, 99, "cachetest", Map.of("p", 1L));
        AtomicInteger loads = new AtomicInteger();
        ResultCache.CachedResult first = cache.getOrLoad(key, Map.of("p", 1L), Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return table(3);
        });
        assertThat(first.status()).isEqualTo(CacheStatus.MISS);
        ResultCache.CachedResult second = cache.getOrLoad(key, Map.of("p", 1L), Duration.ofMinutes(5), () -> {
            loads.incrementAndGet();
            return table(0);
        });
        assertThat(second.status()).isEqualTo(CacheStatus.HIT);
        assertThat(second.cached().table().rows()).hasSize(3);
        assertThat(second.cached().ageSeconds()).isBetween(0L, 5L);
        assertThat(loads.get()).isEqualTo(1);

        // persistent layer survives a memory invalidation
        cache.onQueriesChanged(null);
        assertThat(cache.get(key)).isPresent();
        assertThat(cache.list(10)).anyMatch(e -> e.queryName().equals("Sales") && e.dbUser().equals("cachetest") && e.rowCount() == 3);
        assertThat(cache.stats().entries()).isGreaterThanOrEqualTo(1);

        assertThat(cache.invalidateQueryAndUser(q, "CACHETEST")).isEqualTo(1);
        assertThat(cache.get(key)).isEmpty();

        cache.put(key, Map.of(), table(1), Duration.ofMinutes(1));
        assertThat(cache.invalidateByAdmin(q, null)).isEqualTo(1);
        cache.put(key, Map.of(), table(1), Duration.ofMinutes(1));
        assertThat(cache.invalidateAllByAdmin()).isGreaterThanOrEqualTo(1);
        assertThat(cache.get(key)).isEmpty();
    }

    @Test
    void expiredEntriesAreNotServedAndCleanupRemovesThem() {
        UUID q = queryService.findEnabledByName("Sales").orElseThrow().id();
        CacheKey key = CacheKey.of(q, 98, "expiring", Map.of());
        cache.put(key, Map.of(), table(2), Duration.ofMillis(-1000));
        cache.onQueriesChanged(null);
        assertThat(cache.get(key)).isEmpty();
        long before = repository.count();
        cache.cleanup();
        assertThat(repository.count()).isLessThan(before);
        assertThat(ResultCache.estimateBytes(table(10))).isGreaterThan(64);
    }
}
