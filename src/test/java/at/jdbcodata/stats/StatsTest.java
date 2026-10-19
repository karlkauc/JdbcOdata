package at.jdbcodata.stats;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.cache.CacheStatus;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.testutil.DemoProfileTest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class StatsTest extends DemoProfileTest {

    @Autowired
    ExecutionLogService log;
    @Autowired
    StatsService stats;

    private static ExecutionRecord record(String query, String caller, ExecutionStatus status, CacheStatus cache, long ms, Instant at) {
        return new ExecutionRecord(at, at.plusMillis(ms), UUID.randomUUID(), query, 1, UUID.randomUUID(), "demo", caller,
                caller, ExecutionMode.CALLER, ClientKind.EXCEL, "Excel", "127.0.0.1", "{p=1}", "$top=1", cache,
                status == ExecutionStatus.OK ? ms / 2 : null, ms, status == ExecutionStatus.OK ? 10 : null, null, status,
                status == ExecutionStatus.OK ? null : "boom");
    }

    @Test
    void aggregatesAndFiltersAndExports() {
        String q = "Stat_" + UUID.randomUUID().toString().substring(0, 8);
        String caller = "stat_" + UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        log.record(record(q, caller, ExecutionStatus.OK, CacheStatus.MISS, 100, now.minusSeconds(30)));
        log.record(record(q, caller, ExecutionStatus.OK, CacheStatus.HIT, 10, now.minusSeconds(20)));
        log.record(record(q, caller, ExecutionStatus.DB_ERROR, CacheStatus.DISABLED, 50, now.minusSeconds(10)));
        log.record(record(q, "other", ExecutionStatus.OK, CacheStatus.HIT, 20, now.minus(Duration.ofDays(400))));

        Instant from = now.minus(Duration.ofHours(1));
        Instant to = now.plusSeconds(60);
        List<StatsService.Execution> mine = stats.executions(new StatsService.Filter(from, to, q, caller, null), 100, 0);
        assertThat(mine).hasSize(3);
        assertThat(mine.get(0).status()).isEqualTo("DB_ERROR");
        assertThat(stats.executions(new StatsService.Filter(from, to, q, caller, "OK"), 100, 0)).hasSize(2);
        assertThat(stats.executions(new StatsService.Filter(from, to, q, caller, "OK"), 1, 1)).hasSize(1);

        StatsService.Summary s = stats.summary(from, to);
        assertThat(s.executions()).isGreaterThanOrEqualTo(3);
        assertThat(s.errors()).isGreaterThanOrEqualTo(1);
        assertThat(s.cacheHits()).isGreaterThanOrEqualTo(1);
        assertThat(s.p95TotalMs()).isGreaterThanOrEqualTo(s.p50TotalMs());
        assertThat(s.cacheHitRatio()).isBetween(0.0, 1.0);
        assertThat(s.errorRatio()).isBetween(0.0, 1.0);

        assertThat(stats.topQueries(from, to, 100)).anyMatch(t -> t.queryName().equals(q) && t.executions() == 3 && t.errors() == 1 && t.cacheHits() == 1);
        assertThat(stats.slowestQueries(from, to, 100)).anyMatch(t -> t.queryName().equals(q) && t.maxTotalMs() == 100);
        assertThat(stats.callers(from, to, 100)).anyMatch(c -> c.caller().equals(caller) && c.executions() == 3);
        assertThat(stats.perDay(from, to)).isNotEmpty();

        String csv = stats.toCsv(mine);
        assertThat(csv).startsWith("started_at;query;").contains(q).contains("DB_ERROR").contains("{p=1}");
        assertThat(stats.toCsv(List.of(new StatsService.Execution(UUID.randomUUID(), now, "q;x", 1, null, "c\"d", null, null,
                null, null, null, 5, null, "OK", "line\nbreak", null, null)))).contains("\"q;x\"").contains("\"c\"\"d\"").contains("\"line\nbreak\"");

        assertThat(StatsService.percentile(List.of(), 0.5)).isZero();
        assertThat(StatsService.percentile(List.of(1L, 2L, 3L, 4L), 0.5)).isEqualTo(2L);
        assertThat(StatsService.percentile(List.of(1L, 2L, 3L, 4L), 0.95)).isEqualTo(4L);

        assertThat(new StatsService.Summary(0, 0, 0, 0, 0, 0, 0, 0, 0).cacheHitRatio()).isZero();

        // the scheduled retention job may delete the old record first, so check the outcome, not the count
        log.deleteOlderThan(Duration.ofDays(365));
        assertThat(stats.executions(new StatsService.Filter(now.minus(Duration.ofDays(3650)), now.minus(Duration.ofDays(365)),
                q, null, null), 10, 0)).isEmpty();
        log.applyRetention();
        assertThat(log.droppedRecords()).isZero();
    }

    @Test
    void hourlyBucketsUsagePerQueryAndLastDbExecution() {
        UUID queryId = UUID.randomUUID();
        UUID datasourceId = UUID.randomUUID();
        Instant now = Instant.now();
        log.record(new ExecutionRecord(now.minusSeconds(120), now.minusSeconds(119), queryId, "Usage_q", 1, datasourceId, "ds_x",
                "u", "u", ExecutionMode.CALLER, ClientKind.EXCEL, "Excel", "127.0.0.1", null, null, CacheStatus.MISS, 5L, 9, 3, null,
                ExecutionStatus.OK, null));
        log.record(new ExecutionRecord(now.minusSeconds(60), now.minusSeconds(59), queryId, "Usage_q", 1, datasourceId, "ds_x",
                "u", "u", ExecutionMode.CALLER, ClientKind.EXCEL, "Excel", "127.0.0.1", null, null, CacheStatus.HIT, null, 1, 3, null,
                ExecutionStatus.OK, null));
        log.record(new ExecutionRecord(now.minus(Duration.ofDays(3)), now.minus(Duration.ofDays(3)).plusMillis(4), queryId, "Usage_q", 1,
                datasourceId, "ds_x", "u", "u", ExecutionMode.CALLER, ClientKind.EXCEL, "Excel", "127.0.0.1", null, null,
                CacheStatus.MISS, null, 4, null, null, ExecutionStatus.DB_ERROR, "down"));

        List<StatsService.HourStat> hours = stats.perHour(now, 24);
        assertThat(hours).hasSize(24);
        assertThat(hours.get(23).hour()).isBeforeOrEqualTo(now);
        assertThat(hours.stream().mapToLong(StatsService.HourStat::executions).sum()).isGreaterThanOrEqualTo(2);

        StatsService.QueryUsage usage = stats.usageByQuery(14, java.time.LocalDate.now()).get(queryId);
        assertThat(usage.perDay()).hasSize(14);
        assertThat(usage.total()).isEqualTo(3);
        assertThat(usage.perDay().get(13)).isGreaterThanOrEqualTo(1);
        assertThat(usage.lastCall()).isAfter(now.minusSeconds(61));

        // the cache hit is newer but did not reach the database
        StatsService.LastDbExecution last = stats.lastDbExecutions(now.minus(Duration.ofDays(7))).get(datasourceId);
        assertThat(last.status()).isEqualTo("OK");
        assertThat(last.dbDurationMs()).isEqualTo(5L);
        assertThat(stats.executions(new StatsService.Filter(now.minus(Duration.ofDays(7)), now.plusSeconds(5), "Usage_q", null,
                StatsService.FAILED), 10, 0)).singleElement().extracting(StatsService.Execution::status).isEqualTo("DB_ERROR");
    }

    @Test
    void detectsClients() {
        assertThat(ClientKind.detect(null)).isEqualTo(ClientKind.OTHER);
        assertThat(ClientKind.detect("Microsoft.Data.Mashup (https://go.microsoft.com/fwlink/?LinkID=304225)")).isEqualTo(ClientKind.POWER_QUERY);
        assertThat(ClientKind.detect("PowerBI/2.0")).isEqualTo(ClientKind.POWERBI);
        assertThat(ClientKind.detect("Microsoft Office Excel 2016")).isEqualTo(ClientKind.EXCEL);
        assertThat(ClientKind.detect("Mozilla/5.0 Chrome")).isEqualTo(ClientKind.BROWSER);
        assertThat(ClientKind.detect("curl/8.0")).isEqualTo(ClientKind.OTHER);
    }
}
