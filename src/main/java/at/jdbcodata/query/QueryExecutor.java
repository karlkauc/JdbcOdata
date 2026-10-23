package at.jdbcodata.query;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbErrorClassifier;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.datasource.SessionSettings;
import at.jdbcodata.identity.ExecutionIdentity;
import java.math.BigDecimal;
import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Runs a query under a given database identity and materialises the result. */
@Component
public class QueryExecutor {

    private static final Logger log = LoggerFactory.getLogger(QueryExecutor.class);

    private final IdentityPoolManager pools;
    private final ConcurrencyLimiter limiter;
    private final JdbcOdataProperties props;

    public QueryExecutor(IdentityPoolManager pools, ConcurrencyLimiter limiter, JdbcOdataProperties props) {
        this.pools = pools;
        this.limiter = limiter;
        this.props = props;
    }

    public int effectiveMaxRows(QueryDefinition q, Datasource ds) {
        int limit = Math.min(ds.maxRows(), props.odata().maxRows());
        if (q.maxRows() != null && q.maxRows() > 0) limit = Math.min(limit, q.maxRows());
        return Math.max(limit, 1);
    }

    public int effectiveTimeoutSec(QueryDefinition q, Datasource ds) {
        int timeout = ds.defaultTimeoutSec() > 0 ? ds.defaultTimeoutSec() : props.odata().defaultTimeoutSec();
        if (q.timeoutSec() != null && q.timeoutSec() > 0) timeout = Math.min(timeout, q.timeoutSec());
        return timeout;
    }

    /**
     * @throws at.jdbcodata.datasource.DbAuthenticationException wrong credentials
     * @throws at.jdbcodata.datasource.DbExecutionException      any other database error
     * @throws ResultTooLargeException                            more than {@code maxRows} rows
     */
    public ResultTable execute(QueryDefinition query, Datasource ds, ExecutionIdentity identity,
                               Map<String, Object> params, int maxRows, int timeoutSec) {
        return run(query, ds, identity, params, maxRows, timeoutSec, true, null, false);
    }

    /** Like {@link #execute} with the query wrapped for push-down (WHERE / ORDER BY / paging in the database). */
    public ResultTable execute(QueryDefinition query, Datasource ds, ExecutionIdentity identity,
                               Map<String, Object> params, int maxRows, int timeoutSec, SqlWrapper wrapper) {
        return run(query, ds, identity, params, maxRows, timeoutSec, true, wrapper, false);
    }

    /** {@code SELECT COUNT(*)} of the wrapped query (push-down of $count). */
    public long count(QueryDefinition query, Datasource ds, ExecutionIdentity identity, Map<String, Object> params,
                      int timeoutSec, SqlWrapper wrapper) {
        ResultTable t = run(query, ds, identity, params, 1, timeoutSec, false, wrapper, true);
        Object v = t.rows().isEmpty() ? null : t.rows().get(0)[0];
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    /** Runs the query for a preview: returns at most {@code maxRows} rows and only flags truncation. */
    public ResultTable preview(QueryDefinition query, Datasource ds, ExecutionIdentity identity,
                               Map<String, Object> params, int maxRows, int timeoutSec) {
        return run(query, ds, identity, params, maxRows, timeoutSec, false, null, false);
    }

    /** Fetches a few rows ({@link #SNAPSHOT_SAMPLE_ROWS}) to read the result metadata; never fails because of the row count. */
    public ResultTable probe(QueryDefinition query, Datasource ds, ExecutionIdentity identity,
                             Map<String, Object> params, int timeoutSec) {
        return run(query, ds, identity, params, SNAPSHOT_SAMPLE_ROWS, timeoutSec, false, null, false);
    }

    /** Rows the schema snapshot looks at, e.g. for the structure of JSON columns. */
    public static final int SNAPSHOT_SAMPLE_ROWS = 100;

    private ResultTable run(QueryDefinition query, Datasource ds, ExecutionIdentity identity,
                            Map<String, Object> params, int maxRows, int timeoutSec, boolean failOnTruncation,
                            SqlWrapper wrapper, boolean countOnly) {
        NamedParameterSql parsed = NamedParameterSql.parse(query.sqlText());
        List<ParameterBinder.BoundValue> bound = ParameterBinder.order(parsed, query, params);
        String sql = wrapper == null ? parsed.jdbcSql() : countOnly ? wrapper.count(parsed.jdbcSql()) : wrapper.rows(parsed.jdbcSql());
        List<Object> extra = wrapper == null ? List.of() : countOnly ? wrapper.countBinds() : wrapper.rowBinds();
        long start = System.nanoTime();
        try (ConcurrencyLimiter.Permit permit = limiter.acquire(ds, Duration.ofMillis(props.odata().concurrencyWaitMs()));
             Connection c = pools.connect(ds, SessionSettings.effective(ds, query), identity.dbUsername(),
                     identity.dbPassword())) {
            try {
                c.setReadOnly(true);
            } catch (SQLException ignored) {
                // some drivers do not support read-only connections; the SQL is a SELECT anyway
            }
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                if (timeoutSec > 0) ps.setQueryTimeout(timeoutSec);
                ps.setMaxRows(maxRows + 1);
                ps.setFetchSize(Math.min(1000, maxRows + 1));
                ParameterBinder.bind(ps, bound);
                bindExtra(ps, bound.size(), extra);
                try (ResultSet rs = ps.executeQuery()) {
                    ResultTable table = read(rs, maxRows);
                    if (failOnTruncation && table.truncated()) {
                        throw new ResultTooLargeException(query.name(), maxRows);
                    }
                    log.debug("Query {} as {} on {}: {} rows in {} ms", query.name(), identity.dbUsername(),
                            ds.name(), table.rows().size(), (System.nanoTime() - start) / 1_000_000);
                    return table;
                }
            } finally {
                try {
                    c.rollback();
                } catch (SQLException ignored) {
                    // nothing to roll back for a read-only statement
                }
            }
        } catch (SQLException e) {
            throw DbErrorClassifier.translate(e, ds.name());
        }
    }

    /** Push-down values after the query's own parameters; canonical OData values become JDBC types. */
    static void bindExtra(PreparedStatement ps, int offset, List<Object> values) throws SQLException {
        int i = offset;
        for (Object v : values) {
            i++;
            if (v == null) {
                ps.setNull(i, java.sql.Types.NULL);
            } else if (v instanceof java.time.LocalDate d) {
                ps.setDate(i, java.sql.Date.valueOf(d));
            } else if (v instanceof java.time.Instant t) {
                ps.setTimestamp(i, java.sql.Timestamp.from(t));
            } else if (v instanceof java.time.LocalTime t) {
                ps.setTime(i, java.sql.Time.valueOf(t));
            } else if (v instanceof BigDecimal bd) {
                ps.setBigDecimal(i, bd);
            } else if (v instanceof Long l) {
                ps.setLong(i, l);
            } else if (v instanceof Boolean b) {
                ps.setBoolean(i, b);
            } else {
                ps.setString(i, v.toString());
            }
        }
    }

    static ResultTable read(ResultSet rs, int maxRows) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<ResultColumn> columns = new ArrayList<>(n);
        for (int i = 1; i <= n; i++) {
            int precision = md.getPrecision(i);
            int scale = md.getScale(i);
            columns.add(new ResultColumn(md.getColumnLabel(i), md.getColumnType(i), md.getColumnTypeName(i),
                    precision, scale, md.isNullable(i) != ResultSetMetaData.columnNoNulls,
                    TypeMapper.map(md.getColumnType(i), md.getColumnTypeName(i), precision, scale)));
        }
        List<Object[]> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            Object[] row = new Object[n];
            for (int i = 1; i <= n; i++) {
                row[i - 1] = readValue(rs, i, columns.get(i - 1));
            }
            rows.add(row);
        }
        return new ResultTable(List.copyOf(columns), rows, truncated);
    }

    private static Object readValue(ResultSet rs, int i, ResultColumn column) throws SQLException {
        if (column.jdbcType() == java.sql.Types.ARRAY) return array(rs.getArray(i));
        if (TypeMapper.isJson(column.jdbcTypeName())) return rs.getString(i); // H2 hands out JSON as bytes
        EdmPrimitiveTypeKind kind = column.mapping().kind();
        Object value = switch (kind) {
            case String -> rs.getString(i);
            case Int32 -> rs.getInt(i);
            case Int64 -> rs.getLong(i);
            case Decimal -> rs.getBigDecimal(i);
            case Double -> rs.getDouble(i);
            case Boolean -> rs.getBoolean(i);
            case Date -> rs.getDate(i);
            case TimeOfDay -> rs.getTime(i);
            case DateTimeOffset -> rs.getTimestamp(i);
            case Binary -> rs.getBytes(i);
            case Guid -> {
                Object o = rs.getObject(i);
                yield o == null ? null : (o instanceof UUID u ? u : UUID.fromString(o.toString()));
            }
            default -> {
                Object o = rs.getObject(i);
                yield o == null ? null : o.toString();
            }
        };
        if (rs.wasNull()) return null;
        if (value instanceof BigDecimal bd && bd.scale() < 0) return bd.setScale(0);
        return value;
    }

    /**
     * An SQL array as list of values in the forms the other columns use (Integer, Long, BigDecimal, Double, Boolean,
     * String, java.sql.Date/Time/Timestamp, UUID); nested arrays become nested lists.
     */
    private static List<Object> array(java.sql.Array array) throws SQLException {
        if (array == null) return null;
        try {
            Object raw = array.getArray();
            if (!(raw instanceof Object[] values)) return null;
            List<Object> out = new ArrayList<>(values.length);
            for (Object v : values) out.add(element(v));
            return java.util.Collections.unmodifiableList(out);
        } finally {
            try {
                array.free();
            } catch (SQLException | UnsupportedOperationException ignored) {
                // some drivers do not implement free()
            }
        }
    }

    private static Object element(Object v) throws SQLException {
        return switch (v) {
            case null -> null;
            case Short s -> s.intValue();
            case Byte b -> b.intValue();
            case Float f -> f.doubleValue();
            case BigDecimal bd -> bd.scale() < 0 ? bd.setScale(0) : bd;
            case java.math.BigInteger bi -> new BigDecimal(bi);
            case Integer x -> x;
            case Long x -> x;
            case Double x -> x;
            case Boolean x -> x;
            case String x -> x;
            case java.sql.Date x -> x;
            case java.sql.Time x -> x;
            case java.sql.Timestamp x -> x;
            case UUID x -> x;
            case java.time.LocalDate d -> java.sql.Date.valueOf(d);
            case java.time.LocalTime t -> java.sql.Time.valueOf(t);
            case java.time.LocalDateTime t -> java.sql.Timestamp.valueOf(t);
            case java.time.OffsetDateTime t -> java.sql.Timestamp.from(t.toInstant());
            case Object[] nested -> {
                List<Object> out = new ArrayList<>(nested.length);
                for (Object n : nested) out.add(element(n));
                yield out;
            }
            case java.sql.Array nested -> array(nested);
            default -> v.toString();
        };
    }
}
