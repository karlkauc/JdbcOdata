package at.jdbcodata.query;

import at.jdbcodata.datasource.DatasourceEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "query_definition")
public class QueryDefinitionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 1000)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "datasource_id", nullable = false)
    private DatasourceEntity datasource;

    @Column(name = "sql_text", nullable = false)
    private String sqlText;

    @Enumerated(EnumType.STRING)
    @Column(name = "execution_mode", nullable = false, length = 20)
    private ExecutionMode executionMode = ExecutionMode.CALLER;

    @Column(name = "cache_enabled", nullable = false)
    private boolean cacheEnabled = false;

    @Column(name = "cache_ttl_sec", nullable = false)
    private int cacheTtlSec = 3600;

    @Column(name = "timeout_sec")
    private Integer timeoutSec;

    @Column(name = "max_rows")
    private Integer maxRows;

    @Enumerated(EnumType.STRING)
    @Column(name = "key_mode", nullable = false, length = 20)
    private KeyMode keyMode = KeyMode.ROWID;

    @Column(nullable = false)
    private int version = 1;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "query", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordinal")
    private List<QueryParameterEntity> parameters = new ArrayList<>();

    @OneToMany(mappedBy = "query", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("ordinal")
    private List<QueryColumnEntity> columns = new ArrayList<>();

    public QueryDefinition toRecord() {
        return new QueryDefinition(id, name, description, datasource.getId(), datasource.getName(), sqlText,
                executionMode, cacheEnabled, cacheTtlSec, timeoutSec, maxRows, keyMode, version, enabled,
                parameters.stream().map(QueryParameterEntity::toRecord).toList(),
                columns.stream().map(QueryColumnEntity::toRecord).toList(),
                createdAt, updatedAt);
    }

    public void replaceParameters(List<QueryParameter> params) {
        parameters.clear();
        int i = 0;
        for (QueryParameter p : params) {
            parameters.add(QueryParameterEntity.of(this, i++, p));
        }
    }

    public void replaceColumns(List<QueryColumn> cols) {
        columns.clear();
        for (QueryColumn c : cols) {
            columns.add(QueryColumnEntity.of(this, c));
        }
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public DatasourceEntity getDatasource() { return datasource; }
    public void setDatasource(DatasourceEntity datasource) { this.datasource = datasource; }
    public String getSqlText() { return sqlText; }
    public void setSqlText(String sqlText) { this.sqlText = sqlText; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public void setExecutionMode(ExecutionMode executionMode) { this.executionMode = executionMode; }
    public boolean isCacheEnabled() { return cacheEnabled; }
    public void setCacheEnabled(boolean cacheEnabled) { this.cacheEnabled = cacheEnabled; }
    public int getCacheTtlSec() { return cacheTtlSec; }
    public void setCacheTtlSec(int cacheTtlSec) { this.cacheTtlSec = cacheTtlSec; }
    public Integer getTimeoutSec() { return timeoutSec; }
    public void setTimeoutSec(Integer timeoutSec) { this.timeoutSec = timeoutSec; }
    public Integer getMaxRows() { return maxRows; }
    public void setMaxRows(Integer maxRows) { this.maxRows = maxRows; }
    public KeyMode getKeyMode() { return keyMode; }
    public void setKeyMode(KeyMode keyMode) { this.keyMode = keyMode; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
    public List<QueryParameterEntity> getParameters() { return parameters; }
    public List<QueryColumnEntity> getColumns() { return columns; }
}
