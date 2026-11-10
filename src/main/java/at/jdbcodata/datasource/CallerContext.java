package at.jdbcodata.datasource;

import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.SqlDialect;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;

/**
 * The caller of a request as the database sees it, for row-level security behind a shared technical account. It is
 * set on every checkout of a pooled connection, before the query runs:
 * <ul>
 *   <li>PostgreSQL: {@code current_setting('jdbcodata.caller', true)} and {@code jdbcodata.groups}, local to the
 *       transaction, which ends when the connection goes back to the pool</li>
 *   <li>Oracle: {@code SYS_CONTEXT('USERENV', 'CLIENT_IDENTIFIER')} and {@code 'CLIENT_INFO'}, at most 64 bytes each</li>
 *   <li>MySQL, MariaDB, H2: the session variables {@code @jdbcodata_caller} and {@code @jdbcodata_groups}</li>
 * </ul>
 * Oracle and MySQL keep the values for the session, so connections that carried a caller live in pools of their own
 * (see {@link IdentityPoolManager}) and never serve a query that passes none.
 *
 * @param name   the service user, or the login of a pass-through caller
 * @param groups the names of the service user's groups, sorted; empty for pass-through callers
 */
public record CallerContext(String name, List<String> groups) {

    /** Oracle's limit for the client identifier and the client info. */
    static final int ORACLE_MAX_BYTES = 64;

    public CallerContext {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /** The groups as the database gets them: names separated by commas, which group names cannot contain. */
    public String groupList() {
        return String.join(",", groups);
    }

    /** Distinguishes cached results per caller; see {@code CacheKey}. */
    public String cacheInput() {
        return name + "\n" + groupList();
    }

    /** Does {@code query} pass the caller? The query decides unless it inherits from its datasource. */
    public static boolean enabled(Datasource ds, QueryDefinition query) {
        return switch (query.passCaller()) {
            case ON -> true;
            case OFF -> false;
            case INHERIT -> ds.passCaller();
        };
    }

    /** @throws InvalidRequestException the database of {@code jdbcUrl} has no way to receive the caller */
    public static void checkSupported(String jdbcUrl) {
        if (SqlDialect.of(jdbcUrl) == null) {
            throw new InvalidRequestException("Passing the caller is supported for PostgreSQL, Oracle, MySQL, MariaDB and H2 only");
        }
    }

    /** Hands the caller to the session of {@code connection}, replacing what an earlier checkout left there. */
    public void apply(Connection connection, String jdbcUrl) throws SQLException {
        SqlDialect dialect = SqlDialect.of(jdbcUrl);
        if (dialect == null) throw new DbExecutionException("Passing the caller is not supported for this database", false, null);
        switch (dialect) {
            case POSTGRES -> run(connection, "SELECT set_config('jdbcodata.caller', ?, true), set_config('jdbcodata.groups', ?, true)",
                    name, groupList());
            case ORACLE -> {
                checkLength("caller name", name);
                checkLength("group list", groupList());
                run(connection, "BEGIN DBMS_SESSION.SET_IDENTIFIER(?); DBMS_APPLICATION_INFO.SET_CLIENT_INFO(?); END;",
                        name, groupList());
            }
            case MYSQL -> run(connection, "SET @jdbcodata_caller = ?, @jdbcodata_groups = ?", name, groupList());
            case STANDARD -> {
                run(connection, "SET @jdbcodata_caller = ?", name);
                run(connection, "SET @jdbcodata_groups = ?", groupList());
            }
        }
    }

    private static void checkLength(String what, String value) {
        if (value.getBytes(StandardCharsets.UTF_8).length > ORACLE_MAX_BYTES) {
            throw new DbExecutionException("The " + what + " is longer than the " + ORACLE_MAX_BYTES
                    + " bytes Oracle accepts for the caller", false, null);
        }
    }

    private static void run(Connection connection, String sql, String... values) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setString(i + 1, values[i]);
            statement.execute();
        }
    }
}
