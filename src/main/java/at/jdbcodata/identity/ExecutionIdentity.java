package at.jdbcodata.identity;

import at.jdbcodata.datasource.CallerContext;
import at.jdbcodata.query.ExecutionMode;

/**
 * Who called and under which database identity the query actually runs.
 * {@code caller} is logged; {@code dbUsername}/{@code dbPassword} open the connection. {@code callerContext} is what
 * the database learns about the caller when the query passes it; {@code null} for runs from the admin UI.
 */
public record ExecutionIdentity(String caller, String dbUsername, String dbPassword, ExecutionMode mode,
                                CallerContext callerContext) {

    /** Without a caller for the database, e.g. a test run or a schema snapshot. */
    public ExecutionIdentity(String caller, String dbUsername, String dbPassword, ExecutionMode mode) {
        this(caller, dbUsername, dbPassword, mode, null);
    }

    @Override
    public String toString() {
        return "ExecutionIdentity[caller=" + caller + ", dbUser=" + dbUsername + ", mode=" + mode + "]";
    }
}
