package at.jdbcodata.query;

/** The result exceeded the configured row limit. Maps to HTTP 413. */
public class ResultTooLargeException extends RuntimeException {
    private final int limit;

    public ResultTooLargeException(String queryName, int limit) {
        super("Result of query '" + queryName + "' exceeds the limit of " + limit
                + " rows. Narrow the query with parameters or $top.");
        this.limit = limit;
    }

    public int getLimit() {
        return limit;
    }
}
