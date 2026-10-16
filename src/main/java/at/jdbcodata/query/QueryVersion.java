package at.jdbcodata.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One entry of the version history of a query definition. */
public record QueryVersion(
        UUID id,
        UUID queryId,
        int version,
        String name,
        UUID datasourceId,
        String sqlText,
        List<QueryParameter> parameters,
        String changedBy,
        Instant changedAt,
        String comment) {
}
