package at.jdbcodata.admin.api;

import at.jdbcodata.odata.ODataConfig;
import at.jdbcodata.query.ColumnSetting;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.PrincipalType;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.QuerySpec;
import at.jdbcodata.query.QueryTestService;
import at.jdbcodata.query.QueryVersion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class QueryDtos {

    private QueryDtos() {
    }

    record ParameterRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*") String name,
            @NotNull ParameterType type,
            Boolean required,
            @Size(max = 1000) String defaultValue,
            @Size(max = 1000) String description,
            Boolean sensitive) {

        QueryParameter toRecord() {
            return new QueryParameter(name, type, Boolean.TRUE.equals(required), blankToNull(defaultValue), description,
                    Boolean.TRUE.equals(sensitive));
        }
    }

    /** A relation to another query; {@code localColumns} and {@code targetColumns} are matched by position. */
    record RelationRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*") String name,
            @NotBlank @Size(max = 100) String targetQuery,
            @NotNull List<@NotBlank String> localColumns,
            @NotNull List<@NotBlank String> targetColumns,
            Boolean toMany,
            @Size(max = 100) String partner,
            @Size(max = 1000) String description) {

        QueryRelation toRecord() {
            return new QueryRelation(name, targetQuery, localColumns, targetColumns, Boolean.TRUE.equals(toMany),
                    blankToNull(partner), blankToNull(description));
        }
    }

    record QueryRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*") String name,
            @Size(max = 1000) String description,
            @NotNull UUID datasourceId,
            @NotBlank String sql,
            List<@Valid ParameterRequest> parameters,
            List<@Valid RelationRequest> relations,
            ExecutionMode executionMode,
            UUID fixedCredentialId,
            Boolean cacheEnabled,
            @Min(0) Integer cacheTtlSec,
            @Min(1) @Max(3600) Integer timeoutSec,
            @Min(1) Integer maxRows,
            KeyMode keyMode,
            Boolean enabled,
            Boolean pushDown) {

        QuerySpec toSpec() {
            List<QueryParameter> params = parameters == null ? List.of()
                    : parameters.stream().map(ParameterRequest::toRecord).toList();
            // relations == null keeps the stored relations (PUT without the field), an empty list removes them.
            List<QueryRelation> rels = relations == null ? null : relations.stream().map(RelationRequest::toRecord).toList();
            return new QuerySpec(name, description, datasourceId, sql, executionMode, fixedCredentialId, cacheEnabled, cacheTtlSec,
                    timeoutSec, maxRows, keyMode, enabled, params, rels, pushDown);
        }
    }

    record QueryResponse(
            UUID id,
            String name,
            String description,
            UUID datasourceId,
            String datasourceName,
            String sql,
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
            boolean pushDown,
            boolean hasSnapshot,
            List<QueryParameter> parameters,
            List<QueryColumn> columns,
            List<QueryRelation> relations,
            String feedPath,
            Instant createdAt,
            Instant updatedAt) {

        static QueryResponse from(QueryDefinition q) {
            return new QueryResponse(q.id(), q.name(), q.description(), q.datasourceId(), q.datasourceName(),
                    q.sqlText(), q.executionMode(), q.fixedCredentialId(), q.fixedCredentialName(), q.cacheEnabled(), q.cacheTtlSec(), q.timeoutSec(), q.maxRows(),
                    q.keyMode(), q.version(), q.enabled(), q.pushDown(), q.hasSnapshot(), q.parameters(), q.columns(), q.relations(),
                    ODataConfig.BASE_PATH + "/" + q.name(), q.createdAt(), q.updatedAt());
        }
    }

    /** Credentials used once for the snapshot or test run; they are not stored. */
    record SnapshotRequest(@NotBlank String username, @NotNull String password, Map<String, String> parameters) {
    }

    /** Test run of an unsaved definition straight from the editor. */
    record AdHocTestRequest(@Valid @NotNull QueryRequest query, @NotBlank String username, @NotNull String password,
                            Map<String, String> parameters) {
    }

    record TestRunResponse(List<QueryColumn> columns, List<Map<String, Object>> rows, int rowCount, boolean truncated,
                           int previewLimit, long durationMs) {

        static TestRunResponse from(QueryTestService.TestRunResult r) {
            return new TestRunResponse(r.columns(), r.rows(), r.rows().size(), r.truncated(),
                    QueryTestService.PREVIEW_ROWS, r.durationMs());
        }
    }

    record QueryVersionResponse(int version, String name, UUID datasourceId, String sql, List<QueryParameter> parameters,
                                String changedBy, Instant changedAt, String comment) {

        static QueryVersionResponse from(QueryVersion v) {
            return new QueryVersionResponse(v.version(), v.name(), v.datasourceId(), v.sqlText(), v.parameters(),
                    v.changedBy(), v.changedAt(), v.comment());
        }
    }

    /** {@code collection}: NONE, PRIMITIVE or COMPLEX; omitted = unchanged. */
    record ColumnSettingRequest(@NotBlank String propertyName, boolean key, boolean visible, String collection) {
        ColumnSetting toRecord() {
            return new ColumnSetting(propertyName, key, visible, at.jdbcodata.query.CollectionKind.parse(collection));
        }
    }

    record ColumnSettingsRequest(@NotNull List<@Valid ColumnSettingRequest> columns) {
    }

    record GrantRequest(@NotNull PrincipalType principalType, UUID principalId) {
        QueryService.GrantSpec toSpec() {
            return new QueryService.GrantSpec(principalType, principalId);
        }
    }

    record GrantsRequest(@NotNull List<@Valid GrantRequest> grants) {
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
