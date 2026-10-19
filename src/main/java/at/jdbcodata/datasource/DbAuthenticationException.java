package at.jdbcodata.datasource;

/**
 * The target database rejected the supplied credentials. Maps to HTTP 401 with {@link #CLIENT_MESSAGE}; the message
 * with the datasource name is for logs only.
 */
public class DbAuthenticationException extends RuntimeException {

    public static final String CLIENT_MESSAGE = "The supplied credentials were rejected";

    public DbAuthenticationException(String datasourceName, Throwable cause) {
        super("Database '" + datasourceName + "' rejected the supplied credentials", cause);
    }
}
