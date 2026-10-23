package at.jdbcodata.admin.ui;

import at.jdbcodata.datasource.SessionSettings;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
import at.jdbcodata.query.QueryRelation;
import at.jdbcodata.query.QuerySpec;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class QueryForm {

    public static class ParameterForm {
        @NotBlank @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*", message = "a letter, then letters, digits or _")
        private String name;
        @NotNull
        private ParameterType type = ParameterType.STRING;
        private boolean required;
        @Size(max = 1000)
        private String defaultValue;
        @Size(max = 1000)
        private String description;
        private boolean sensitive;

        static ParameterForm from(QueryParameter p) {
            ParameterForm f = new ParameterForm();
            f.name = p.name();
            f.type = p.type();
            f.required = p.required();
            f.defaultValue = p.defaultValue();
            f.description = p.description();
            f.sensitive = p.sensitive();
            return f;
        }

        QueryParameter toRecord() {
            return new QueryParameter(name, type, required, blankToNull(defaultValue), blankToNull(description), sensitive);
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public ParameterType getType() { return type; }
        public void setType(ParameterType type) { this.type = type; }
        public boolean isRequired() { return required; }
        public void setRequired(boolean required) { this.required = required; }
        public String getDefaultValue() { return defaultValue; }
        public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public boolean isSensitive() { return sensitive; }
        public void setSensitive(boolean sensitive) { this.sensitive = sensitive; }
    }

    /** One row of the relations table; column lists are entered comma-separated. */
    public static class RelationForm {
        @Size(max = 100) @Pattern(regexp = "|[A-Za-z][A-Za-z0-9_]*", message = "a letter, then letters, digits or _")
        private String name;
        @Size(max = 100)
        private String targetQuery;
        @Size(max = 1000)
        private String localColumns;
        @Size(max = 1000)
        private String targetColumns;
        private boolean toMany;
        @Size(max = 100)
        private String partner;
        @Size(max = 1000)
        private String description;

        static RelationForm from(QueryRelation r) {
            RelationForm f = new RelationForm();
            f.name = r.name();
            f.targetQuery = r.targetQuery();
            f.localColumns = String.join(", ", r.localColumns());
            f.targetColumns = String.join(", ", r.targetColumns());
            f.toMany = r.toMany();
            f.partner = r.partner();
            f.description = r.description();
            return f;
        }

        QueryRelation toRecord() {
            return new QueryRelation(name, blankToNull(targetQuery), splitColumns(localColumns), splitColumns(targetColumns),
                    toMany, blankToNull(partner), blankToNull(description));
        }

        private static List<String> splitColumns(String s) {
            if (s == null || s.isBlank()) return List.of();
            return java.util.Arrays.stream(s.split("[,;\\s]+")).filter(c -> !c.isBlank()).toList();
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getTargetQuery() { return targetQuery; }
        public void setTargetQuery(String targetQuery) { this.targetQuery = targetQuery; }
        public String getLocalColumns() { return localColumns; }
        public void setLocalColumns(String localColumns) { this.localColumns = localColumns; }
        public String getTargetColumns() { return targetColumns; }
        public void setTargetColumns(String targetColumns) { this.targetColumns = targetColumns; }
        public boolean isToMany() { return toMany; }
        public void setToMany(boolean toMany) { this.toMany = toMany; }
        public String getPartner() { return partner; }
        public void setPartner(String partner) { this.partner = partner; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z][A-Za-z0-9_]*", message = "a letter, then letters, digits or _")
    private String name;
    @Size(max = 1000)
    private String description;
    @NotNull
    private UUID datasourceId;
    @NotBlank
    private String sql;
    @Valid
    private List<ParameterForm> parameters = new ArrayList<>();
    @Valid
    private List<RelationForm> relations = new ArrayList<>();
    private ExecutionMode executionMode = ExecutionMode.CALLER;
    private UUID fixedCredentialId;
    private boolean cacheEnabled;
    @Min(0)
    private Integer cacheTtlSec = 3600;
    @Min(1) @Max(3600)
    private Integer timeoutSec;
    @Min(1)
    private Integer maxRows;
    private KeyMode keyMode = KeyMode.ROWID;
    private boolean enabled = true;
    private boolean pushDown;
    @Size(max = 10_000)
    private String sessionSettings = "";

    public static QueryForm from(QueryDefinition q) {
        QueryForm f = new QueryForm();
        f.name = q.name();
        f.description = q.description();
        f.datasourceId = q.datasourceId();
        f.sql = q.sqlText();
        f.parameters = new ArrayList<>(q.parameters().stream().map(ParameterForm::from).toList());
        f.relations = new ArrayList<>(q.relations().stream().map(RelationForm::from).toList());
        f.executionMode = q.executionMode();
        f.fixedCredentialId = q.fixedCredentialId();
        f.cacheEnabled = q.cacheEnabled();
        f.cacheTtlSec = q.cacheTtlSec();
        f.timeoutSec = q.timeoutSec();
        f.maxRows = q.maxRows();
        f.keyMode = q.keyMode();
        f.enabled = q.enabled();
        f.pushDown = q.pushDown();
        f.sessionSettings = SessionSettings.toLines(q.sessionSettings());
        return f;
    }

    public QuerySpec toSpec() {
        List<QueryParameter> params = parameters == null ? List.of()
                : parameters.stream().filter(p -> p != null && p.name != null && !p.name.isBlank())
                        .map(ParameterForm::toRecord).toList();
        List<QueryRelation> rels = relations == null ? List.of()
                : relations.stream().filter(r -> r != null && r.name != null && !r.name.isBlank())
                        .map(RelationForm::toRecord).toList();
        return new QuerySpec(name, blankToNull(description), datasourceId, sql, executionMode, fixedCredentialId, cacheEnabled,
                cacheTtlSec, timeoutSec, maxRows, keyMode, enabled, params, rels, pushDown,
                SessionSettings.fromLines(sessionSettings));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getDatasourceId() { return datasourceId; }
    public void setDatasourceId(UUID datasourceId) { this.datasourceId = datasourceId; }
    public String getSql() { return sql; }
    public void setSql(String sql) { this.sql = sql; }
    public List<ParameterForm> getParameters() { return parameters; }
    public void setParameters(List<ParameterForm> parameters) { this.parameters = parameters; }
    public List<RelationForm> getRelations() { return relations; }
    public void setRelations(List<RelationForm> relations) { this.relations = relations; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public UUID getFixedCredentialId() { return fixedCredentialId; }
    public void setFixedCredentialId(UUID fixedCredentialId) { this.fixedCredentialId = fixedCredentialId; }
    public void setExecutionMode(ExecutionMode executionMode) { this.executionMode = executionMode; }
    public boolean isCacheEnabled() { return cacheEnabled; }
    public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }
    public Integer getCacheTtlSec() { return cacheTtlSec; }
    public void setCacheTtlSec(Integer cacheTtlSec) { this.cacheTtlSec = cacheTtlSec; }
    public Integer getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(Integer timeoutSec) { this.timeoutSec = timeoutSec; }
    public Integer getMaxRows() { return maxRows; }
    public void setMaxRows(Integer maxRows) { this.maxRows = maxRows; }
    public KeyMode getKeyMode() { return keyMode; }
    public void setKeyMode(KeyMode keyMode) { this.keyMode = keyMode; }
    public boolean isEnabled() { return enabled; }
    public boolean isPushDown() { return pushDown; }
    public void setPushDown(boolean pushDown) { this.pushDown = pushDown; }
    public String getSessionSettings() { return sessionSettings; }
    public void setSessionSettings(String sessionSettings) { this.sessionSettings = sessionSettings; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
