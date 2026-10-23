package at.jdbcodata.query;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Input for creating or updating a query definition. Null values keep the current value or the default; session
 * settings override single keys of the datasource defaults.
 */
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
        List<QueryRelation> relations,
        Boolean pushDown,
        Map<String, String> sessionSettings) {

    /** Without session settings: keeps the current overrides on update, none on create. */
    public QuerySpec(String name, String description, UUID datasourceId, String sqlText, ExecutionMode executionMode,
                     UUID fixedCredentialId, Boolean cacheEnabled, Integer cacheTtlSec, Integer timeoutSec, Integer maxRows,
                     KeyMode keyMode, Boolean enabled, List<QueryParameter> parameters, List<QueryRelation> relations,
                     Boolean pushDown) {
        this(name, description, datasourceId, sqlText, executionMode, fixedCredentialId, cacheEnabled, cacheTtlSec,
                timeoutSec, maxRows, keyMode, enabled, parameters, relations, pushDown, null);
    }

    /** Without the push-down flag: keeps the current value on update, off on create. */
    public QuerySpec(String name, String description, UUID datasourceId, String sqlText, ExecutionMode executionMode,
                     UUID fixedCredentialId, Boolean cacheEnabled, Integer cacheTtlSec, Integer timeoutSec, Integer maxRows,
                     KeyMode keyMode, Boolean enabled, List<QueryParameter> parameters, List<QueryRelation> relations) {
        this(name, description, datasourceId, sqlText, executionMode, fixedCredentialId, cacheEnabled, cacheTtlSec,
                timeoutSec, maxRows, keyMode, enabled, parameters, relations, null);
    }

    /** Without relations: keeps the current relations on update, none on create. */
    public QuerySpec(String name, String description, UUID datasourceId, String sqlText, ExecutionMode executionMode,
                     UUID fixedCredentialId, Boolean cacheEnabled, Integer cacheTtlSec, Integer timeoutSec, Integer maxRows,
                     KeyMode keyMode, Boolean enabled, List<QueryParameter> parameters) {
        this(name, description, datasourceId, sqlText, executionMode, fixedCredentialId, cacheEnabled, cacheTtlSec,
                timeoutSec, maxRows, keyMode, enabled, parameters, null, null);
    }
}
