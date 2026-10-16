package at.jdbcodata.query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Immutable view of a query definition including parameters and the schema snapshot. */
public record QueryDefinition(
        UUID id,
        String name,
        String description,
        UUID datasourceId,
        String datasourceName,
        String sqlText,
        ExecutionMode executionMode,
        UUID fixedCredentialId,
        String fixedCredentialName,
        boolean cacheEnabled,
        int cacheTtlSec,
        Integer timeoutSec,
        Integer maxRows,
        KeyMode keyMode,
        int version,
        boolean enabled,
        List<QueryParameter> parameters,
        List<QueryColumn> columns,
        Instant createdAt,
        Instant updatedAt) {

    public boolean hasSnapshot() {
        return columns != null && !columns.isEmpty();
    }
}
