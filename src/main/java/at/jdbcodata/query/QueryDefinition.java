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
        Instant updatedAt,
        List<QueryRelation> relations) {

    public QueryDefinition {
        relations = relations == null ? List.of() : List.copyOf(relations);
    }

    /** Without relations (kept for the many call sites that predate them). */
    public QueryDefinition(UUID id, String name, String description, UUID datasourceId, String datasourceName,
                           String sqlText, ExecutionMode executionMode, UUID fixedCredentialId, String fixedCredentialName,
                           boolean cacheEnabled, int cacheTtlSec, Integer timeoutSec, Integer maxRows, KeyMode keyMode,
                           int version, boolean enabled, List<QueryParameter> parameters, List<QueryColumn> columns,
                           Instant createdAt, Instant updatedAt) {
        this(id, name, description, datasourceId, datasourceName, sqlText, executionMode, fixedCredentialId,
                fixedCredentialName, cacheEnabled, cacheTtlSec, timeoutSec, maxRows, keyMode, version, enabled, parameters,
                columns, createdAt, updatedAt, List.of());
    }

    public boolean hasSnapshot() {
        return columns != null && !columns.isEmpty();
    }
}
