package at.jdbcodata.query;

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
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "query_parameter")
public class QueryParameterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "query_id", nullable = false)
    private QueryDefinitionEntity query;

    @Column(nullable = false)
    private int ordinal;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "param_type", nullable = false, length = 20)
    private ParameterType type;

    @Column(nullable = false)
    private boolean required;

    @Column(name = "default_value", length = 1000)
    private String defaultValue;

    @Column(length = 1000)
    private String description;

    @Column(nullable = false)
    private boolean sensitive;

    static QueryParameterEntity of(QueryDefinitionEntity query, int ordinal, QueryParameter p) {
        QueryParameterEntity e = new QueryParameterEntity();
        e.query = query;
        e.ordinal = ordinal;
        e.name = p.name();
        e.type = p.type();
        e.required = p.required();
        e.defaultValue = p.defaultValue();
        e.description = p.description();
        e.sensitive = p.sensitive();
        return e;
    }

    QueryParameter toRecord() {
        return new QueryParameter(name, type, required, defaultValue, description, sensitive);
    }
}
