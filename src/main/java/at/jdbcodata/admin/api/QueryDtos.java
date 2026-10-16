package at.jdbcodata.admin.api;

import at.jdbcodata.odata.ODataConfig;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class QueryDtos {

    private QueryDtos() {
    }

    record QueryResponse(
            UUID id,
            String name,
            String description,
            UUID datasourceId,
            String datasourceName,
            String sql,
            ExecutionMode executionMode,
            boolean cacheEnabled,
            int cacheTtlSec,
            Integer timeoutSec,
            Integer maxRows,
            KeyMode keyMode,
            int version,
            boolean enabled,
            boolean hasSnapshot,
            List<QueryParameter> parameters,
            List<QueryColumn> columns,
            String feedPath,
            Instant createdAt,
            Instant updatedAt) {

        static QueryResponse from(QueryDefinition q) {
            return new QueryResponse(q.id(), q.name(), q.description(), q.datasourceId(), q.datasourceName(),
                    q.sqlText(), q.executionMode(), q.cacheEnabled(), q.cacheTtlSec(), q.timeoutSec(), q.maxRows(),
                    q.keyMode(), q.version(), q.enabled(), q.hasSnapshot(), q.parameters(), q.columns(),
                    ODataConfig.BASE_PATH + "/" + q.name(), q.createdAt(), q.updatedAt());
        }
    }

    /** Credentials used once for the snapshot run; they are not stored. */
    record SnapshotRequest(@NotBlank String username, @NotNull String password, Map<String, String> parameters) {
    }
}
