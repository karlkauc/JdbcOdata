package at.jdbcodata.query;

import java.util.List;
import java.util.UUID;

/** Input for creating or updating a query definition. Null values keep the current value or the default. */
public record QuerySpec(
        String name,
        String description,
        UUID datasourceId,
        String sqlText,
        ExecutionMode executionMode,
        UUID fixedCredentialId,
        Boolean cacheEnabled,
        Integer cacheTtlSec,
        Integer timeoutSec,
        Integer maxRows,
        KeyMode keyMode,
        Boolean enabled,
        List<QueryParameter> parameters,
        List<QueryRelation> relations) {

    /** Without relations: keeps the current relations on update, none on create. */
    public QuerySpec(String name, String description, UUID datasourceId, String sqlText, ExecutionMode executionMode,
                     UUID fixedCredentialId, Boolean cacheEnabled, Integer cacheTtlSec, Integer timeoutSec, Integer maxRows,
                     KeyMode keyMode, Boolean enabled, List<QueryParameter> parameters) {
        this(name, description, datasourceId, sqlText, executionMode, fixedCredentialId, cacheEnabled, cacheTtlSec,
                timeoutSec, maxRows, keyMode, enabled, parameters, null);
    }
}
