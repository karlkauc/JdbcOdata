package at.jdbcodata.query;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps a query's SQL for push-down: {@code SELECT * FROM (<query>) jdbcodata_q [WHERE …] [ORDER BY …] [paging]}.
 * The WHERE and ORDER BY fragments refer to the query's result columns (quoted labels) and use {@code ?} placeholders
 * whose values follow the query's own parameters. With {@code inner}, the wrapper applies to the result of that
 * wrapper instead (e.g. a filter on the result of a GROUP BY). {@code numbered} adds the column
 * {@code jdbcodata_rn}: the position of each row in the query result, for keeping the order of first appearance.
 */
public record SqlWrapper(SqlDialect dialect, String where, List<Object> whereBinds, String orderBy, Integer offset,
                         Integer fetch, String select, String groupBy, SqlWrapper inner, boolean numbered, int levels) {

    public static final String ROW_NUMBER = "jdbcodata_rn";
    /** With {@code levels > 0}: every row once per level 1…levels in this column (rollup in one scan). */
    public static final String LEVEL = "jdbcodata_lvl";

    public SqlWrapper(SqlDialect dialect, String where, List<Object> whereBinds, String orderBy, Integer offset,
                      Integer fetch, String select, String groupBy, SqlWrapper inner, boolean numbered) {
        this(dialect, where, whereBinds, orderBy, offset, fetch, select, groupBy, inner, numbered, 0);
    }

    public SqlWrapper {
        whereBinds = whereBinds == null ? List.of() : java.util.Collections.unmodifiableList(new ArrayList<>(whereBinds));
    }

    public SqlWrapper(SqlDialect dialect, String where, List<Object> whereBinds, String orderBy, Integer offset,
                      Integer fetch, String select, String groupBy, SqlWrapper inner) {
        this(dialect, where, whereBinds, orderBy, offset, fetch, select, groupBy, inner, false, 0);
    }

    public SqlWrapper(SqlDialect dialect, String where, List<Object> whereBinds, String orderBy, Integer offset,
                      Integer fetch, String select, String groupBy) {
        this(dialect, where, whereBinds, orderBy, offset, fetch, select, groupBy, null, false, 0);
    }

    public SqlWrapper(SqlDialect dialect, String where, List<Object> whereBinds, String orderBy, Integer offset, Integer fetch) {
        this(dialect, where, whereBinds, orderBy, offset, fetch, null, null, null, false, 0);
    }

    /** The wrapped SQL for {@code querySql} (already in JDBC form with {@code ?} for the query parameters). */
    public String rows(String querySql) {
        String source = source(querySql);
        StringBuilder sql = new StringBuilder("SELECT ").append(select == null || select.isBlank() ? "*" : select)
                .append(" FROM (").append(source).append(") jdbcodata_q");
        if (where != null && !where.isBlank()) sql.append(" WHERE ").append(where);
        if (groupBy != null && !groupBy.isBlank()) sql.append(" GROUP BY ").append(groupBy);
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

    private String source(String querySql) {
        String source = inner == null ? querySql : inner.rows(querySql);
        if (numbered) {
            source = "SELECT jdbcodata_s.*, " + dialect.rowNumber() + " AS " + ROW_NUMBER + " FROM (" + source + ") jdbcodata_s";
        }
        if (levels > 0) {
            StringBuilder l = new StringBuilder();
            for (int i = 1; i <= levels; i++) {
                if (i > 1) l.append(" UNION ALL ");
                l.append("SELECT ").append(i).append(" AS ").append(LEVEL).append(dialect == SqlDialect.ORACLE ? " FROM DUAL" : "");
            }
            source = "SELECT jdbcodata_n.*, jdbcodata_l." + LEVEL + " FROM (" + source + ") jdbcodata_n CROSS JOIN (" + l + ") jdbcodata_l";
        }
        return source;
    }

    /** Values for the placeholders added by {@link #rows}. */
    public List<Object> rowBinds() {
        List<Object> binds = new ArrayList<>(inner == null ? List.of() : inner.rowBinds());
        binds.addAll(whereBinds);
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
        String source = source(querySql);
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM (").append(source).append(") jdbcodata_q");
        if (where != null && !where.isBlank()) sql.append(" WHERE ").append(where);
        return sql.toString();
    }

    /** Values for the placeholders added by {@link #count}. */
    public List<Object> countBinds() {
        List<Object> binds = new ArrayList<>(inner == null ? List.of() : inner.rowBinds());
        binds.addAll(whereBinds);
        return binds;
    }

    /** Stable text for cache keys and logs. */
    public String fingerprint() {
        return rows("<q>") + " " + rowBinds();
    }

    public boolean pagingPushed() {
        return offset != null || fetch != null;
    }
}
