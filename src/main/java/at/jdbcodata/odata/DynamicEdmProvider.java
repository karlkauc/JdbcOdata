package at.jdbcodata.odata;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlAbstractEdmProvider;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainer;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainerInfo;
import org.apache.olingo.commons.api.edm.provider.CsdlEntitySet;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlPropertyRef;
import org.apache.olingo.commons.api.edm.provider.CsdlSchema;

/** Entity model derived from query definitions: one entity set and one entity type per query. */
public class DynamicEdmProvider extends CsdlAbstractEdmProvider {

    public static final String NAMESPACE = "JdbcOdata";
    public static final String CONTAINER_NAME = "Container";
    public static final FullQualifiedName CONTAINER = new FullQualifiedName(NAMESPACE, CONTAINER_NAME);
    public static final String ROW_ID = "RowId";

    private final Map<String, QueryDefinition> queries = new LinkedHashMap<>();

    public DynamicEdmProvider(List<QueryDefinition> definitions) {
        for (QueryDefinition q : definitions) {
            queries.put(q.name(), q);
        }
    }

    public static FullQualifiedName typeName(String queryName) {
        return new FullQualifiedName(NAMESPACE, queryName);
    }

    @Override
    public CsdlEntityType getEntityType(FullQualifiedName entityTypeName) {
        if (!NAMESPACE.equals(entityTypeName.getNamespace())) return null;
        QueryDefinition q = queries.get(entityTypeName.getName());
        return q == null ? null : buildType(q);
    }

    @Override
    public CsdlEntitySet getEntitySet(FullQualifiedName entityContainer, String entitySetName) {
        if (!CONTAINER.equals(entityContainer) || !queries.containsKey(entitySetName)) return null;
        return new CsdlEntitySet().setName(entitySetName).setType(typeName(entitySetName));
    }

    @Override
    public CsdlEntityContainer getEntityContainer() {
        List<CsdlEntitySet> sets = new ArrayList<>();
        for (String name : queries.keySet()) {
            sets.add(getEntitySet(CONTAINER, name));
        }
        return new CsdlEntityContainer().setName(CONTAINER_NAME).setEntitySets(sets);
    }

    @Override
    public CsdlEntityContainerInfo getEntityContainerInfo(FullQualifiedName entityContainerName) {
        if (entityContainerName == null || CONTAINER.equals(entityContainerName)) {
            return new CsdlEntityContainerInfo().setContainerName(CONTAINER);
        }
        return null;
    }

    @Override
    public List<CsdlSchema> getSchemas() {
        List<CsdlEntityType> types = new ArrayList<>();
        for (QueryDefinition q : queries.values()) {
            types.add(buildType(q));
        }
        CsdlSchema schema = new CsdlSchema().setNamespace(NAMESPACE).setEntityTypes(types)
                .setEntityContainer(getEntityContainer());
        return List.of(schema);
    }

    static CsdlEntityType buildType(QueryDefinition q) {
        List<CsdlProperty> props = new ArrayList<>();
        List<CsdlPropertyRef> keys = new ArrayList<>();
        if (q.keyMode() == KeyMode.ROWID) {
            props.add(new CsdlProperty().setName(ROW_ID)
                    .setType(EdmPrimitiveTypeKind.Int64.getFullQualifiedName()).setNullable(false));
            keys.add(new CsdlPropertyRef().setName(ROW_ID));
        }
        for (QueryColumn c : q.columns()) {
            if (!c.visible()) continue;
            CsdlProperty p = new CsdlProperty().setName(c.propertyName())
                    .setType(EdmPrimitiveTypeKind.valueOfFQN(c.edmType()).getFullQualifiedName())
                    .setNullable(!(q.keyMode() == KeyMode.COLUMNS && c.key()));
            if (c.precision() != null) p.setPrecision(c.precision());
            if (c.scale() != null) p.setScale(c.scale());
            props.add(p);
            if (q.keyMode() == KeyMode.COLUMNS && c.key()) {
                keys.add(new CsdlPropertyRef().setName(c.propertyName()));
            }
        }
        if (keys.isEmpty()) {
            props.add(0, new CsdlProperty().setName(ROW_ID)
                    .setType(EdmPrimitiveTypeKind.Int64.getFullQualifiedName()).setNullable(false));
            keys.add(new CsdlPropertyRef().setName(ROW_ID));
        }
        return new CsdlEntityType().setName(q.name()).setProperties(props).setKey(keys);
    }
}
