package at.jdbcodata.identity;

/** The caller may not see a FIXED-mode query. Maps to HTTP 404, never 403, so that names are not enumerable. */
public class QueryNotVisibleException extends RuntimeException {
    public QueryNotVisibleException(String queryName) {
        super("Entity set '" + queryName + "' not found");
    }
}
