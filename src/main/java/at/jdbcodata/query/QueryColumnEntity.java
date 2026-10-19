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
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "query_column")
public class QueryColumnEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "query_id", nullable = false)
    private QueryDefinitionEntity query;

    @Column(nullable = false)
    private int ordinal;

    @Column(name = "column_name", nullable = false, length = 200)
    private String columnName;

    @Column(name = "property_name", nullable = false, length = 200)
    private String propertyName;

    @Column(name = "jdbc_type", nullable = false)
    private int jdbcType;

    @Column(name = "jdbc_type_name", length = 100)
    private String jdbcTypeName;

    @Column(name = "edm_type", nullable = false, length = 50)
    private String edmType;

    @Column(name = "col_precision")
    private Integer precision;

    @Column(name = "col_scale")
    private Integer scale;

    @Column(nullable = false)
    private boolean nullable = true;

    @Column(name = "is_key", nullable = false)
    private boolean key;

    @Column(nullable = false)
    private boolean visible = true;

    @Column(name = "element_type", length = 50)
    private String elementType;

    /** {@link ComplexField}s as JSON array. */
    @Column(name = "structure_json", columnDefinition = "TEXT")
    private String structureJson;

    @Enumerated(EnumType.STRING)
    @Column(name = "collection_kind", nullable = false, length = 10)
    private CollectionKind collection = CollectionKind.NONE;

    @Column(name = "structure_single", nullable = false)
    private boolean singleObject;

    @Column(name = "text_copy", nullable = false)
    private boolean textCopy;

    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder().build();
    private static final tools.jackson.core.type.TypeReference<List<ComplexField>> FIELDS = new tools.jackson.core.type.TypeReference<>() {
    };

    static QueryColumnEntity of(QueryDefinitionEntity query, QueryColumn c) {
        QueryColumnEntity e = new QueryColumnEntity();
        e.query = query;
        e.ordinal = c.ordinal();
        e.columnName = c.name();
        e.propertyName = c.propertyName();
        e.jdbcType = c.jdbcType();
        e.jdbcTypeName = c.jdbcTypeName();
        e.edmType = c.edmType();
        e.precision = c.precision();
        e.scale = c.scale();
        e.nullable = c.nullable();
        e.key = c.key();
        e.visible = c.visible();
        e.elementType = c.elementType();
        e.structureJson = c.structure().isEmpty() ? null : JSON.writeValueAsString(c.structure());
        e.collection = c.collection();
        e.singleObject = c.singleObject();
        e.textCopy = c.textCopy();
        return e;
    }

    String getPropertyName() { return propertyName; }
    void setKey(boolean key) { this.key = key; }
    void setVisible(boolean visible) { this.visible = visible; }
    void setCollection(CollectionKind collection) { this.collection = collection; }
    void setTextCopy(boolean textCopy) { this.textCopy = textCopy; }
    void setStructure(List<ComplexField> structure) {
        this.structureJson = structure == null || structure.isEmpty() ? null : JSON.writeValueAsString(structure);
    }

    QueryColumn toRecord() {
        return new QueryColumn(ordinal, columnName, propertyName, jdbcType, jdbcTypeName, edmType, precision, scale,
                nullable, key, visible, elementType,
                structureJson == null || structureJson.isBlank() ? List.of() : JSON.readValue(structureJson, FIELDS), collection,
                singleObject, textCopy);
    }
}
