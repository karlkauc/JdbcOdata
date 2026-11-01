package at.jdbcodata.datasource;

/** All execution slots of a datasource are busy. Maps to HTTP 503 with Retry-After. */
public class TooManyExecutionsException extends RuntimeException {
    public TooManyExecutionsException(String message) {
        super(message);
    }

    public TooManyExecutionsException(String datasourceName, int limit) {
        super("Datasource '" + datasourceName + "' is busy (" + limit + " parallel executions). Try again shortly.");
    }
}
