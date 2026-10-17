package at.jdbcodata.odata;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryRelation;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.provider.CsdlAbstractEdmProvider;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainer;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityContainerInfo;
import org.apache.olingo.commons.api.edm.provider.CsdlEntitySet;
import org.apache.olingo.commons.api.edm.provider.CsdlEntityType;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlNavigationPropertyBinding;
import org.apache.olingo.commons.api.edm.provider.CsdlProperty;
import org.apache.olingo.commons.api.edm.provider.CsdlPropertyRef;
import org.apache.olingo.commons.api.edm.provider.CsdlReferentialConstraint;
import org.apache.olingo.commons.api.edm.provider.CsdlSchema;

/**
 * Entity model derived from query definitions: one entity set and one entity type per query. Relations between
 * queries become navigation properties and navigation property bindings; a relation whose target is not part of
 * this (personalised) model is left out, so a caller never learns about feeds they may not see.
 */
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
        return q == null ? null : buildType(q).setNavigationProperties(navigationProperties(q));
    }

    @Override
    public CsdlEntitySet getEntitySet(FullQualifiedName entityContainer, String entitySetName) {
        if (!CONTAINER.equals(entityContainer) || !queries.containsKey(entitySetName)) return null;
        List<CsdlNavigationPropertyBinding> bindings = new ArrayList<>();
        for (QueryRelation r : visibleRelations(queries.get(entitySetName))) {
            bindings.add(new CsdlNavigationPropertyBinding().setPath(r.name()).setTarget(r.targetQuery()));
        }
        return new CsdlEntitySet().setName(entitySetName).setType(typeName(entitySetName))
                .setNavigationPropertyBindings(bindings);
    }

    /** Relations whose target query is part of this model and whose name does not clash with a property. */
    private List<QueryRelation> visibleRelations(QueryDefinition q) {
        List<QueryRelation> result = new ArrayList<>();
        Set<String> taken = new HashSet<>(propertyNames(q));
        for (QueryRelation r : q.relations()) {
            if (!queries.containsKey(r.targetQuery()) || !taken.add(r.name())) continue;
            result.add(r);
        }
        return result;
    }

    private static List<String> propertyNames(QueryDefinition q) {
        List<String> names = new ArrayList<>();
        for (QueryColumn c : q.columns()) {
            if (c.visible()) names.add(c.propertyName());
        }
        if (q.keyMode() == KeyMode.ROWID || q.columns().stream().noneMatch(c -> c.visible() && c.key())) {
            names.add(ROW_ID);
        }
        return names;
    }

    private List<CsdlNavigationProperty> navigationProperties(QueryDefinition q) {
        List<CsdlNavigationProperty> navs = new ArrayList<>();
        for (QueryRelation r : visibleRelations(q)) {
            QueryDefinition target = queries.get(r.targetQuery());
            // Nullable stays at Olingo's default (true): the serializer unboxes it, so never pass null.
            CsdlNavigationProperty nav = new CsdlNavigationProperty().setName(r.name()).setType(typeName(r.targetQuery()))
                    .setCollection(r.toMany());
            // Only a partner that is itself published (target exists, no name clash) may be referenced: Olingo
            // resolves partners while serialising and fails the whole $metadata on a dangling name.
            if (r.partner() != null && !r.partner().isBlank()
                    && visibleRelations(target).stream().anyMatch(p -> p.name().equals(r.partner()) && q.name().equals(p.targetQuery()))) {
                nav.setPartner(r.partner());
            }
            if (!r.toMany()) {
                List<String> own = propertyNames(q);
                List<String> theirs = propertyNames(target);
                List<CsdlReferentialConstraint> constraints = new ArrayList<>();
                for (int i = 0; i < Math.min(r.localColumns().size(), r.targetColumns().size()); i++) {
                    String local = r.localColumns().get(i);
                    String referenced = r.targetColumns().get(i);
                    if (own.contains(local) && theirs.contains(referenced)) {
                        constraints.add(new CsdlReferentialConstraint().setProperty(local).setReferencedProperty(referenced));
                    }
                }
                nav.setReferentialConstraints(constraints);
            }
            navs.add(nav);
        }
        return navs;
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
            types.add(buildType(q).setNavigationProperties(navigationProperties(q)));
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
