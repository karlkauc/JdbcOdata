package at.jdbcodata.stats;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Aggregations over the execution log (plain SQL, portable between H2 in PostgreSQL mode and PostgreSQL). */
@Service
@Transactional(readOnly = true)
public class StatsService {

    public record Summary(long executions, long cacheHits, long errors, long rows, double avgTotalMs, long p50TotalMs,
                          long p95TotalMs, double avgDbMs, long distinctCallers) {
        public double cacheHitRatio() {
            return executions == 0 ? 0 : (double) cacheHits / executions;
        }

        public double errorRatio() {
            return executions == 0 ? 0 : (double) errors / executions;
        }
    }

    public record QueryStat(String queryName, long executions, long errors, long cacheHits, double avgTotalMs, long maxTotalMs, long rows) {
    }

    public record CallerStat(String caller, long executions, long errors, long rows, Instant lastSeen) {
    }

    public record DayStat(LocalDate day, long executions, long errors, long cacheHits) {
    }

    /** One hour bucket; {@code hour} is the start of the hour. */
    public record HourStat(Instant hour, long executions, long errors, long cacheHits, double avgTotalMs) {
    }

    /** Calls of one query: {@code perDay} oldest day first, {@code lastCall} over the whole retained log. */
    public record QueryUsage(List<Long> perDay, long total, Instant lastCall) {
    }

    /** Latest execution that reached the database (cache hits excluded). */
    public record LastDbExecution(Instant startedAt, String status, String errorMessage, Long dbDurationMs) {
    }

    public record Filter(Instant from, Instant to, String queryName, String caller, String status) {
    }

    public record Execution(UUID id, Instant startedAt, String queryName, Integer queryVersion, String datasourceName,
                            String caller, String effectiveDbUser, String executionMode, String client, String cacheStatus,
                            Long dbDurationMs, long totalDurationMs, Integer rowCount, String status, String errorMessage,
                            String parameters, String odataOptions) {
    }

    /** Pseudo status of {@link Filter#status()}: every execution that did not end with OK. */
    public static final String FAILED = "FAILED";

    private final JdbcTemplate jdbc;

    public StatsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Summary summary(Instant from, Instant to) {
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT COUNT(*) AS n,
                       COALESCE(SUM(CASE WHEN cache_status = 'HIT' THEN 1 ELSE 0 END), 0) AS hits,
                       COALESCE(SUM(CASE WHEN status <> 'OK' THEN 1 ELSE 0 END), 0) AS errors,
                       COALESCE(SUM(row_count), 0) AS rows_total,
                       COALESCE(AVG(total_duration_ms), 0) AS avg_total,
                       COALESCE(AVG(db_duration_ms), 0) AS avg_db,
                       COUNT(DISTINCT caller) AS callers
                FROM execution_log WHERE started_at >= ? AND started_at < ?""", Timestamp.from(from), Timestamp.from(to));
        List<Long> durations = jdbc.queryForList(
                "SELECT total_duration_ms FROM execution_log WHERE started_at >= ? AND started_at < ? ORDER BY total_duration_ms",
                Long.class, Timestamp.from(from), Timestamp.from(to));
        return new Summary(num(row.get("n")), num(row.get("hits")), num(row.get("errors")), num(row.get("rows_total")),
                dbl(row.get("avg_total")), percentile(durations, 0.5), percentile(durations, 0.95), dbl(row.get("avg_db")),
                num(row.get("callers")));
    }

    public List<QueryStat> topQueries(Instant from, Instant to, int limit) {
        return jdbc.query("""
                SELECT query_name, COUNT(*) AS n, SUM(CASE WHEN status <> 'OK' THEN 1 ELSE 0 END) AS errors,
                       SUM(CASE WHEN cache_status = 'HIT' THEN 1 ELSE 0 END) AS hits,
                       AVG(total_duration_ms) AS avg_total, MAX(total_duration_ms) AS max_total, COALESCE(SUM(row_count), 0) AS rows_total
                FROM execution_log WHERE started_at >= ? AND started_at < ?
                GROUP BY query_name ORDER BY n DESC, query_name""",
                (rs, i) -> new QueryStat(rs.getString("query_name"), rs.getLong("n"), rs.getLong("errors"), rs.getLong("hits"),
                        rs.getDouble("avg_total"), rs.getLong("max_total"), rs.getLong("rows_total")),
                Timestamp.from(from), Timestamp.from(to)).stream().limit(limit).toList();
    }

    public List<QueryStat> slowestQueries(Instant from, Instant to, int limit) {
        return topQueries(from, to, Integer.MAX_VALUE).stream()
                .sorted((a, b) -> Double.compare(b.avgTotalMs(), a.avgTotalMs())).limit(limit).toList();
    }

    public List<CallerStat> callers(Instant from, Instant to, int limit) {
        return jdbc.query("""
                SELECT caller, COUNT(*) AS n, SUM(CASE WHEN status <> 'OK' THEN 1 ELSE 0 END) AS errors,
                       COALESCE(SUM(row_count), 0) AS rows_total, MAX(started_at) AS last_seen
                FROM execution_log WHERE started_at >= ? AND started_at < ?
                GROUP BY caller ORDER BY n DESC, caller""",
                (rs, i) -> new CallerStat(rs.getString("caller"), rs.getLong("n"), rs.getLong("errors"), rs.getLong("rows_total"),
                        rs.getTimestamp("last_seen").toInstant()),
                Timestamp.from(from), Timestamp.from(to)).stream().limit(limit).toList();
    }

    public List<DayStat> perDay(Instant from, Instant to) {
        return jdbc.query("""
                SELECT CAST(started_at AS DATE) AS d, COUNT(*) AS n, SUM(CASE WHEN status <> 'OK' THEN 1 ELSE 0 END) AS errors,
                       SUM(CASE WHEN cache_status = 'HIT' THEN 1 ELSE 0 END) AS hits
                FROM execution_log WHERE started_at >= ? AND started_at < ?
                GROUP BY CAST(started_at AS DATE) ORDER BY d""",
                (rs, i) -> new DayStat(rs.getDate("d").toLocalDate(), rs.getLong("n"), rs.getLong("errors"), rs.getLong("hits")),
                Timestamp.from(from), Timestamp.from(to));
    }

    /** {@code hours} consecutive hour buckets ending with the hour of {@code to}; hours without executions are zero. */
    public List<HourStat> perHour(Instant to, int hours) {
        Instant last = to.truncatedTo(ChronoUnit.HOURS);
        Instant first = last.minus(hours - 1L, ChronoUnit.HOURS);
        Map<Instant, HourStat> found = new HashMap<>();
        jdbc.query("""
                SELECT DATE_TRUNC('hour', started_at) AS h, COUNT(*) AS n, SUM(CASE WHEN status <> 'OK' THEN 1 ELSE 0 END) AS errors,
                       SUM(CASE WHEN cache_status = 'HIT' THEN 1 ELSE 0 END) AS hits, AVG(total_duration_ms) AS avg_total
                FROM execution_log WHERE started_at >= ? AND started_at < ?
                GROUP BY DATE_TRUNC('hour', started_at)""",
                rs -> {
                    Instant h = rs.getTimestamp("h").toInstant().truncatedTo(ChronoUnit.HOURS);
                    found.put(h, new HourStat(h, rs.getLong("n"), rs.getLong("errors"), rs.getLong("hits"), rs.getDouble("avg_total")));
                },
                Timestamp.from(first), Timestamp.from(last.plus(1, ChronoUnit.HOURS)));
        List<HourStat> result = new ArrayList<>(hours);
        for (Instant h = first; !h.isAfter(last); h = h.plus(1, ChronoUnit.HOURS)) {
            result.add(found.getOrDefault(h, new HourStat(h, 0, 0, 0, 0)));
        }
        return result;
    }

    /** Calls per query id over the last {@code days} days (today included) and the last call of every query. */
    public Map<UUID, QueryUsage> usageByQuery(int days, LocalDate today) {
        LocalDate first = today.minusDays(days - 1L);
        Map<UUID, long[]> counts = new HashMap<>();
        jdbc.query("""
                SELECT query_id, CAST(started_at AS DATE) AS d, COUNT(*) AS n FROM execution_log
                WHERE query_id IS NOT NULL AND started_at >= ?
                GROUP BY query_id, CAST(started_at AS DATE)""",
                rs -> {
                    int idx = (int) ChronoUnit.DAYS.between(first, rs.getDate("d").toLocalDate());
                    if (idx >= 0 && idx < days) {
                        counts.computeIfAbsent(UUID.fromString(rs.getString("query_id")), k -> new long[days])[idx] += rs.getLong("n");
                    }
                },
                Timestamp.valueOf(first.atStartOfDay()));
        Map<UUID, Instant> lastCalls = new HashMap<>();
        jdbc.query("SELECT query_id, MAX(started_at) AS last_call FROM execution_log WHERE query_id IS NOT NULL GROUP BY query_id",
                rs -> {
                    lastCalls.put(UUID.fromString(rs.getString("query_id")), rs.getTimestamp("last_call").toInstant());
                });
        Map<UUID, QueryUsage> result = new HashMap<>();
        for (UUID id : lastCalls.keySet()) {
            long[] c = counts.getOrDefault(id, new long[days]);
            result.put(id, new QueryUsage(Arrays.stream(c).boxed().toList(), Arrays.stream(c).sum(), lastCalls.get(id)));
        }
        return result;
    }

    /** Latest execution per datasource id that went to the database, within the given window. */
    public Map<UUID, LastDbExecution> lastDbExecutions(Instant from) {
        Map<UUID, LastDbExecution> result = new HashMap<>();
        jdbc.query("""
                SELECT e.datasource_id, e.started_at, e.status, e.error_message, e.db_duration_ms
                FROM execution_log e
                JOIN (SELECT datasource_id, MAX(started_at) AS last_at FROM execution_log
                      WHERE datasource_id IS NOT NULL AND cache_status <> 'HIT' AND started_at >= ?
                      GROUP BY datasource_id) x ON x.datasource_id = e.datasource_id AND x.last_at = e.started_at
                WHERE e.cache_status <> 'HIT'""",
                rs -> {
                    UUID id = UUID.fromString(rs.getString("datasource_id"));
                    result.putIfAbsent(id, new LastDbExecution(rs.getTimestamp("started_at").toInstant(), rs.getString("status"),
                            rs.getString("error_message"), (Long) rs.getObject("db_duration_ms", Long.class)));
                },
                Timestamp.from(from));
        return result;
    }

    public List<Execution> executions(Filter f, int limit, int offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, started_at, query_name, query_version, datasource_name, caller, effective_db_user, execution_mode,
                       client, cache_status, db_duration_ms, total_duration_ms, row_count, status, error_message, parameters, odata_options
                FROM execution_log WHERE started_at >= ? AND started_at < ?""");
        List<Object> args = new ArrayList<>(List.of(Timestamp.from(f.from()), Timestamp.from(f.to())));
        if (f.queryName() != null && !f.queryName().isBlank()) {
            sql.append(" AND query_name = ?");
            args.add(f.queryName());
        }
        if (f.caller() != null && !f.caller().isBlank()) {
            sql.append(" AND LOWER(caller) = LOWER(?)");
            args.add(f.caller());
        }
        if (FAILED.equals(f.status())) {
            sql.append(" AND status <> 'OK'");
        } else if (f.status() != null && !f.status().isBlank()) {
            sql.append(" AND status = ?");
            args.add(f.status());
        }
        sql.append(" ORDER BY started_at DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(limit, 10_000)));
        args.add(Math.max(0, offset));
        return jdbc.query(sql.toString(), (rs, i) -> new Execution(
                UUID.fromString(rs.getString("id")), rs.getTimestamp("started_at").toInstant(), rs.getString("query_name"),
                (Integer) rs.getObject("query_version"), rs.getString("datasource_name"), rs.getString("caller"),
                rs.getString("effective_db_user"), rs.getString("execution_mode"), rs.getString("client"), rs.getString("cache_status"),
                (Long) rs.getObject("db_duration_ms", Long.class), rs.getLong("total_duration_ms"), (Integer) rs.getObject("row_count"),
                rs.getString("status"), rs.getString("error_message"), rs.getString("parameters"), rs.getString("odata_options")),
                args.toArray());
    }

    public String toCsv(List<Execution> rows) {
        StringBuilder sb = new StringBuilder("started_at;query;version;datasource;caller;db_user;mode;client;cache;db_ms;total_ms;rows;status;error;parameters;odata_options\n");
        for (Execution e : rows) {
            sb.append(csv(e.startedAt())).append(';').append(csv(e.queryName())).append(';').append(csv(e.queryVersion())).append(';')
                    .append(csv(e.datasourceName())).append(';').append(csv(e.caller())).append(';').append(csv(e.effectiveDbUser())).append(';')
                    .append(csv(e.executionMode())).append(';').append(csv(e.client())).append(';').append(csv(e.cacheStatus())).append(';')
                    .append(csv(e.dbDurationMs())).append(';').append(e.totalDurationMs()).append(';').append(csv(e.rowCount())).append(';')
                    .append(csv(e.status())).append(';').append(csv(e.errorMessage())).append(';').append(csv(e.parameters())).append(';')
                    .append(csv(e.odataOptions())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (s.contains(";") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static double dbl(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }
}
