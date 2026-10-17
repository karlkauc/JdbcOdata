package at.jdbcodata.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "query_relation")
public class QueryRelationEntity {

    private static final String SEPARATOR = ",";

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

    @Column(name = "target_query", nullable = false, length = 100)
    private String targetQuery;

    /** Comma-separated property names of this query. */
    @Column(name = "local_columns", nullable = false, length = 1000)
    private String localColumns;

    /** Comma-separated property names of the target query, same count as {@link #localColumns}. */
    @Column(name = "target_columns", nullable = false, length = 1000)
    private String targetColumns;

    @Column(name = "to_many", nullable = false)
    private boolean toMany;

    @Column(length = 100)
    private String partner;

    @Column(length = 1000)
    private String description;

    static QueryRelationEntity of(QueryDefinitionEntity query, int ordinal, QueryRelation r) {
        QueryRelationEntity e = new QueryRelationEntity();
        e.query = query;
        e.ordinal = ordinal;
        e.name = r.name();
        e.targetQuery = r.targetQuery();
        e.localColumns = String.join(SEPARATOR, r.localColumns());
        e.targetColumns = String.join(SEPARATOR, r.targetColumns());
        e.toMany = r.toMany();
        e.partner = r.partner();
        e.description = r.description();
        return e;
    }

    QueryRelation toRecord() {
        return new QueryRelation(name, targetQuery, split(localColumns), split(targetColumns), toMany, partner, description);
    }

    private static List<String> split(String joined) {
        if (joined == null || joined.isBlank()) return List.of();
        return Arrays.stream(joined.split(SEPARATOR)).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
