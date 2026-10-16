package at.jdbcodata.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_version")
public class QueryVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "sql_text", nullable = false)
    private String sqlText;

    @Column(name = "parameters_json", nullable = false)
    private String parametersJson;

    @Column(name = "changed_by", length = 100)
    private String changedBy;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(length = 1000)
    private String comment;

    static QueryVersionEntity of(QueryDefinitionEntity q, String changedBy, String comment) {
        QueryVersionEntity v = new QueryVersionEntity();
        v.queryId = q.getId();
        v.version = q.getVersion();
        v.name = q.getName();
        v.datasourceId = q.getDatasource().getId();
        v.sqlText = q.getSqlText();
        v.parametersJson = ParametersJson.write(q.getParameters().stream().map(QueryParameterEntity::toRecord).toList());
        v.changedBy = changedBy;
        v.changedAt = Instant.now();
        v.comment = comment;
        return v;
    }

    QueryVersion toRecord() {
        return new QueryVersion(id, queryId, version, name, datasourceId, sqlText, ParametersJson.read(parametersJson),
                changedBy, changedAt, comment);
    }

    public int getVersion() { return version; }
}
