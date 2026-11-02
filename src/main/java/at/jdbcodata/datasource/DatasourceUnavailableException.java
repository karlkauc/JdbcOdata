package at.jdbcodata.datasource;

/** The circuit breaker of a datasource is open. Maps to HTTP 503 with Retry-After, without a connection attempt. */
public class DatasourceUnavailableException extends DbExecutionException {
    public DatasourceUnavailableException(String datasourceName) {
        super("Datasource '" + datasourceName + "' is not reachable. Try again shortly.", false, null);
    }
}
