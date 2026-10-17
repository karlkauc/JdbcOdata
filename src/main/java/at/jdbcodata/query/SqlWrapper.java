package at.jdbcodata.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a query's SQL for push-down: {@code SELECT * FROM (<query>) jdbcodata_q [WHERE …] [ORDER BY …] [paging]}.
 * The WHERE and ORDER BY fragments refer to the query's result columns (quoted labels) and use {@code ?} placeholders
 * whose values follow the query's own parameters.
 */
public record SqlWrapper(SqlDialect dialect, String where, List<Object> whereBinds, String orderBy, Integer offset,
                         Integer fetch) {

    public SqlWrapper {
        whereBinds = whereBinds == null ? List.of() : List.copyOf(whereBinds);
    }

    /** The wrapped SQL for {@code querySql} (already in JDBC form with {@code ?} for the query parameters). */
    public String rows(String querySql) {
        StringBuilder sql = new StringBuilder("SELECT * FROM (").append(querySql).append(") jdbcodata_q");
        if (where != null && !where.isBlank()) sql.append(" WHERE ").append(where);
        if (orderBy != null && !orderBy.isBlank()) sql.append(" ORDER BY ").append(orderBy);
        if (offset != null || fetch != null) {
            if (dialect == SqlDialect.MYSQL) {
                sql.append(" LIMIT ? OFFSET ?");
            } else {
                sql.append(" OFFSET ? ROWS");
                if (fetch != null) sql.append(" FETCH NEXT ? ROWS ONLY");
            }
        }
        return sql.toString();
    }

    /** Values for the placeholders added by {@link #rows}. */
    public List<Object> rowBinds() {
        List<Object> binds = new ArrayList<>(whereBinds);
        if (offset != null || fetch != null) {
            int off = offset == null ? 0 : offset;
            if (dialect == SqlDialect.MYSQL) {
                binds.add(fetch == null ? Long.MAX_VALUE : (long) fetch);
                binds.add((long) off);
            } else {
                binds.add((long) off);
                if (fetch != null) binds.add((long) fetch);
            }
        }
        return binds;
    }

    /** {@code SELECT COUNT(*)} with the same WHERE, for {@code $count} when paging was pushed down. */
    public String count(String querySql) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM (").append(querySql).append(") jdbcodata_q");
        if (where != null && !where.isBlank()) sql.append(" WHERE ").append(where);
        return sql.toString();
    }

    /** Stable text for cache keys and logs. */
    public String fingerprint() {
        return rows("<q>") + " " + rowBinds();
    }

    public boolean pagingPushed() {
        return offset != null || fetch != null;
    }
}
