package at.jdbcodata.transfer;

import at.jdbcodata.query.CollectionKind;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.PassCaller;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.PrincipalType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Configuration of an instance without secrets and without service users, for moving it from one environment to the
 * next (export, then import with dry run). Entries are matched by name. A missing field ({@code null}) keeps the value
 * of the target, or the default for new entries; a missing section is left alone.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConfigDocument(
        Integer format,
        String exportedFrom,
        Instant exportedAt,
        List<DatasourceEntry> datasources,
        List<FixedCredentialEntry> fixedCredentials,
        List<GroupEntry> groups,
        List<QueryEntry> queries) {

    public static final int FORMAT = 1;

    /** Connection data ({@code jdbcUrl}, {@code driverClass}, {@code driverProperties}) belongs to the environment. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DatasourceEntry(String name, String description, String jdbcUrl, String driverClass, String timezone,
                                  Integer defaultTimeoutSec, Integer maxRows, Integer maxConcurrent, Integer poolSize,
                                  Boolean enabled, Boolean passCaller, Map<String, String> sessionSettings,
                                  Map<String, String> driverProperties) {
    }

    /** Technical account. {@code dbPassword} is never exported; an import needs it only for new accounts. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FixedCredentialEntry(String name, String description, String datasource, String dbUsername,
                                       String dbPassword) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GroupEntry(String name, String description) {
    }

    /** {@code columns} is the schema snapshot with the column settings; empty or missing keeps the target's. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record QueryEntry(String name, String description, String datasource, String sql,
                             ExecutionMode executionMode, String fixedCredential, Boolean enabled, KeyMode keyMode,
                             Boolean pushDown, PassCaller passCaller, Boolean cacheEnabled, Integer cacheTtlSec, Integer timeoutSec,
                             Integer maxRows, Map<String, String> sessionSettings, List<ParameterEntry> parameters,
                             List<RelationEntry> relations, List<ColumnEntry> columns, List<GrantEntry> grants) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ParameterEntry(String name, ParameterType type, Boolean required,
                                 @JsonProperty("default") String defaultValue, String description, Boolean sensitive) {
    }

    /** {@code columns}: own property name → property name of the target query. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RelationEntry(String name, String target, Map<String, String> columns, Boolean toMany,
                                String partner, String description) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ColumnEntry(String name, String propertyName, Integer jdbcType, String jdbcTypeName, String edmType,
                              Integer precision, Integer scale, Boolean nullable, Boolean key, Boolean visible,
                              String elementType, List<FieldEntry> structure, CollectionKind collection,
                              Boolean singleObject, Boolean textCopy) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldEntry(String key, String name, String edmType, Boolean collection) {
    }

    /** {@code name} of the service user or group; none for {@code ALL_AUTHENTICATED}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record GrantEntry(PrincipalType type, String name) {
    }
}
