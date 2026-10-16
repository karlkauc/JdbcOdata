package at.jdbcodata.datasource;

/** Any other database error (connection, SQL, timeout). The message is safe to show to clients. */
public class DbExecutionException extends RuntimeException {
    private final boolean timeout;

    public DbExecutionException(String safeMessage, boolean timeout, Throwable cause) {
        super(safeMessage, cause);
        this.timeout = timeout;
    }

    public boolean isTimeout() {
        return timeout;
    }
}
