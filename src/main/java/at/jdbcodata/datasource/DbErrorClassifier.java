package at.jdbcodata.datasource;

import java.sql.SQLException;
import java.sql.SQLTimeoutException;

/** Translates JDBC exceptions of the supported vendors into the two application exceptions. */
public final class DbErrorClassifier {

    private DbErrorClassifier() {
    }

    public static RuntimeException translate(Throwable t, String datasourceName) {
        SQLException sql = findSqlException(t);
        if (sql == null) {
            return new DbExecutionException("Database error on '" + datasourceName + "'", false, t);
        }
        if (isAuthFailure(sql)) {
            return new DbAuthenticationException(datasourceName, sql);
        }
        if (sql.getMessage() != null && sql.getMessage().startsWith("No suitable driver")) {
            return new DbExecutionException("No JDBC driver for the URL of '" + datasourceName
                    + "'; put the driver JAR into lib/ (Oracle is not bundled)", false, sql);
        }
        boolean timeout = sql instanceof SQLTimeoutException
                || "57014".equals(sql.getSQLState())      // PostgreSQL query_canceled
                || sql.getErrorCode() == 1013              // Oracle ORA-01013 user requested cancel
                || sql.getErrorCode() == 3024              // MySQL max_execution_time
                || sql.getErrorCode() == 57014;            // H2 statement timeout
        return new DbExecutionException(describe(sql), timeout, sql);
    }

    public static boolean isAuthFailure(SQLException e) {
        for (SQLException s = e; s != null; s = s.getNextException()) {
            String state = s.getSQLState();
            if (state != null && state.startsWith("28")) return true;   // SQL standard: invalid authorization
            if (s.getErrorCode() == 1017) return true;                  // Oracle ORA-01017
            if (s.getErrorCode() == 1045) return true;                  // MySQL/MariaDB access denied
            if (s.getErrorCode() == 28000) return true;                 // H2 wrong user or password
        }
        return false;
    }

    /** Short vendor message without SQL text; the full exception is logged server side. */
    public static String describe(SQLException e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        int nl = msg.indexOf('\n');
        if (nl > 0) msg = msg.substring(0, nl);
        int sqlIdx = msg.indexOf("; SQL statement:");
        if (sqlIdx > 0) msg = msg.substring(0, sqlIdx);
        if (msg.length() > 300) msg = msg.substring(0, 300) + "…";
        return e.getSQLState() != null ? msg + " [SQLState " + e.getSQLState() + "]" : msg;
    }

    private static SQLException findSqlException(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException s) return s;
            if (c.getCause() == c) break;
        }
        return null;
    }
}
