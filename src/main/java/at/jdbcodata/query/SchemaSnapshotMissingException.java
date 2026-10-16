package at.jdbcodata.query;

/** The query has no persisted column metadata yet. Maps to HTTP 503. */
public class SchemaSnapshotMissingException extends RuntimeException {
    public SchemaSnapshotMissingException(String queryName) {
        super("Query '" + queryName + "' has no schema snapshot yet. An admin must run a snapshot "
                + "(POST /api/v1/queries/{id}/snapshot) before it can be served.");
    }
}
