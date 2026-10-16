package at.jdbcodata.identity;

import at.jdbcodata.query.ExecutionMode;

/**
 * Who called and under which database identity the query actually runs.
 * {@code caller} is logged; {@code dbUsername}/{@code dbPassword} open the connection.
 */
public record ExecutionIdentity(String caller, String dbUsername, String dbPassword, ExecutionMode mode) {

    @Override
    public String toString() {
        return "ExecutionIdentity[caller=" + caller + ", dbUser=" + dbUsername + ", mode=" + mode + "]";
    }
}
