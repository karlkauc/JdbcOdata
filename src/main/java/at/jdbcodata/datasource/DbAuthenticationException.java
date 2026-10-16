package at.jdbcodata.datasource;

/** The target database rejected the supplied credentials. Maps to HTTP 401. */
public class DbAuthenticationException extends RuntimeException {
    public DbAuthenticationException(String datasourceName, Throwable cause) {
        super("Database '" + datasourceName + "' rejected the supplied credentials", cause);
    }
}
