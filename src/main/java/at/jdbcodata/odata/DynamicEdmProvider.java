package at.jdbcodata.odata;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.ParameterType;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryParameter;
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
import org.apache.olingo.commons.api.edm.provider.CsdlFunction;
import org.apache.olingo.commons.api.edm.provider.CsdlFunctionImport;
import org.apache.olingo.commons.api.edm.provider.CsdlParameter;
import org.apache.olingo.commons.api.edm.provider.CsdlReturnType;
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
        return q == null ? null : annotatedType(q);
    }

    @Override
    public CsdlEntitySet getEntitySet(FullQualifiedName entityContainer, String entitySetName) {
        if (!CONTAINER.equals(entityContainer) || !queries.containsKey(entitySetName)) return null;
        List<CsdlNavigationPropertyBinding> bindings = new ArrayList<>();
        for (QueryRelation r : visibleRelations(queries.get(entitySetName))) {
            bindings.add(new CsdlNavigationPropertyBinding().setPath(r.name()).setTarget(r.targetQuery()));
        }
        return new CsdlEntitySet().setName(entitySetName).setType(typeName(entitySetName))
                .setNavigationPropertyBindings(bindings).setAnnotations(entitySetCapabilities(queries.get(entitySetName)));
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
            if (r.description() != null && !r.description().isBlank()) nav.setAnnotations(List.of(description(r.description())));
            navs.add(nav);
        }
        return navs;
    }

    @Override
    public CsdlEntityContainer getEntityContainer() {
        List<CsdlEntitySet> sets = new ArrayList<>();
        List<CsdlFunctionImport> imports = new ArrayList<>();
        for (String name : queries.keySet()) {
            sets.add(getEntitySet(CONTAINER, name));
            CsdlFunctionImport fi = getFunctionImport(CONTAINER, FUNCTION_PREFIX + name);
            if (fi != null) imports.add(fi);
        }
        return new CsdlEntityContainer().setName(CONTAINER_NAME).setEntitySets(sets).setFunctionImports(imports)
                .setAnnotations(List.of(
                        tag(CAPABILITIES, "BatchSupported", true),
                        tag(CAPABILITIES, "KeyAsSegmentSupported", true),
                        tag(CAPABILITIES, "CrossJoinSupported", true),
                        tag(CAPABILITIES, "AsynchronousRequestsSupported", false),
                        stringCollection(AGGREGATION + ".CustomAggregationMethods",
                                ApplyEvaluator.CUSTOM_METHODS.stream().map(m -> NAMESPACE + "." + m).toList())));
    }

    // --- vocabulary annotations ($metadata documentation and capabilities) -------------------------------------

    public static final String CORE = "Org.OData.Core.V1";
    public static final String CAPABILITIES = "Org.OData.Capabilities.V1";
    public static final String AGGREGATION = "Org.OData.Aggregation.V1";

    /** Terms used here; Olingo writes an annotation only when its term resolves. */
    private static final Map<String, String> TERMS = Map.ofEntries(
            Map.entry(CORE + ".Description", "Edm.String"),
            Map.entry(CAPABILITIES + ".BatchSupported", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".KeyAsSegmentSupported", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".CrossJoinSupported", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".AsynchronousRequestsSupported", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".TopSupported", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".SkipSupported", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".IndexableByKey", "Org.OData.Core.V1.Tag"),
            Map.entry(CAPABILITIES + ".CountRestrictions", CAPABILITIES + ".CountRestrictionsType"),
            Map.entry(CAPABILITIES + ".SearchRestrictions", CAPABILITIES + ".SearchRestrictionsType"),
            Map.entry(CAPABILITIES + ".FilterRestrictions", CAPABILITIES + ".FilterRestrictionsType"),
            Map.entry(CAPABILITIES + ".SortRestrictions", CAPABILITIES + ".SortRestrictionsType"),
            Map.entry(CAPABILITIES + ".ExpandRestrictions", CAPABILITIES + ".ExpandRestrictionsType"),
            Map.entry(CAPABILITIES + ".InsertRestrictions", CAPABILITIES + ".InsertRestrictionsType"),
            Map.entry(CAPABILITIES + ".UpdateRestrictions", CAPABILITIES + ".UpdateRestrictionsType"),
            Map.entry(CAPABILITIES + ".DeleteRestrictions", CAPABILITIES + ".DeleteRestrictionsType"),
            Map.entry(CAPABILITIES + ".ChangeTracking", CAPABILITIES + ".ChangeTrackingType"),
            Map.entry(AGGREGATION + ".ApplySupported", AGGREGATION + ".ApplySupportedType"),
            Map.entry(AGGREGATION + ".CustomAggregationMethods", "Collection(" + AGGREGATION + ".QualifiedName)"),
            Map.entry(AGGREGATION + ".RecursiveHierarchy", AGGREGATION + ".RecursiveHierarchyType"));

    @Override
    public org.apache.olingo.commons.api.edm.provider.CsdlTerm getTerm(FullQualifiedName termName) {
        String type = TERMS.get(termName.getFullQualifiedNameAsString());
        return type == null ? null : new org.apache.olingo.commons.api.edm.provider.CsdlTerm()
                .setName(termName.getName()).setType(type);
    }

    /** {@code $metadata} references to the vocabularies, with the usual aliases. */
    public static List<org.apache.olingo.commons.api.edmx.EdmxReference> vocabularyReferences() {
        List<org.apache.olingo.commons.api.edmx.EdmxReference> refs = new ArrayList<>();
        for (String[] v : new String[][] {{CORE, "Core"}, {CAPABILITIES, "Capabilities"}, {AGGREGATION, "Aggregation"}}) {
            org.apache.olingo.commons.api.edmx.EdmxReference ref = new org.apache.olingo.commons.api.edmx.EdmxReference(
                    java.net.URI.create("https://oasis-tcs.github.io/odata-vocabularies/vocabularies/" + v[0] + ".xml"));
            ref.addInclude(new org.apache.olingo.commons.api.edmx.EdmxReferenceInclude(v[0], v[1]));
            refs.add(ref);
        }
        return refs;
    }

    private CsdlEntityType annotatedType(QueryDefinition q) {
        CsdlEntityType type = buildType(q).setNavigationProperties(navigationProperties(q));
        List<org.apache.olingo.commons.api.edm.provider.CsdlAnnotation> annotations = new ArrayList<>();
        if (q.description() != null && !q.description().isBlank()) annotations.add(description(q.description()));
        annotations.addAll(recursiveHierarchies(q));
        type.setAnnotations(annotations);
        return type;
    }

    /**
     * {@code Aggregation.RecursiveHierarchy#<Relation>} for every to-one relation of a query to itself with one
     * column pair: the local column is the parent reference, the target column the node property. The relation
     * name is the hierarchy qualifier for ancestors/descendants/traverse.
     */
    private List<org.apache.olingo.commons.api.edm.provider.CsdlAnnotation> recursiveHierarchies(QueryDefinition q) {
        List<org.apache.olingo.commons.api.edm.provider.CsdlAnnotation> out = new ArrayList<>();
        for (QueryRelation r : visibleRelations(q)) {
            if (r.toMany() || !q.name().equals(r.targetQuery()) || r.targetColumns().size() != 1) continue;
            List<org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue> values = List.of(
                    new org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue().setProperty("NodeProperty")
                            .setValue(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyPath().setValue(r.targetColumns().get(0))),
                    new org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue().setProperty("ParentNavigationProperty")
                            .setValue(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlNavigationPropertyPath().setValue(r.name())));
            out.add(new org.apache.olingo.commons.api.edm.provider.CsdlAnnotation().setTerm(AGGREGATION + ".RecursiveHierarchy")
                    .setQualifier(r.name())
                    .setExpression(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlRecord()
                            .setType(AGGREGATION + ".RecursiveHierarchyType").setPropertyValues(values)));
        }
        return out;
    }

    private static org.apache.olingo.commons.api.edm.provider.CsdlAnnotation stringCollection(String term, List<String> items) {
        List<org.apache.olingo.commons.api.edm.provider.annotation.CsdlExpression> list = new ArrayList<>();
        for (String i : items) {
            list.add(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression(
                    org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression.ConstantExpressionType.String, i));
        }
        return new org.apache.olingo.commons.api.edm.provider.CsdlAnnotation().setTerm(term)
                .setExpression(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlCollection().setItems(list));
    }

    static org.apache.olingo.commons.api.edm.provider.CsdlAnnotation description(String text) {
        return new org.apache.olingo.commons.api.edm.provider.CsdlAnnotation().setTerm(CORE + ".Description")
                .setExpression(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression(
                        org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression.ConstantExpressionType.String, text));
    }

    private static org.apache.olingo.commons.api.edm.provider.CsdlAnnotation tag(String namespace, String term, boolean value) {
        return new org.apache.olingo.commons.api.edm.provider.CsdlAnnotation().setTerm(namespace + "." + term)
                .setExpression(bool(value));
    }

    private static org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression bool(boolean value) {
        return new org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression(
                org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression.ConstantExpressionType.Bool,
                String.valueOf(value));
    }

    private static org.apache.olingo.commons.api.edm.provider.CsdlAnnotation record(String term, Object... nameValue) {
        List<org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue> values = new ArrayList<>();
        for (int i = 0; i < nameValue.length; i += 2) {
            org.apache.olingo.commons.api.edm.provider.annotation.CsdlExpression v = nameValue[i + 1] instanceof Boolean b ? bool(b)
                    : new org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression(
                            org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression.ConstantExpressionType.Int,
                            String.valueOf(nameValue[i + 1]));
            values.add(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue()
                    .setProperty((String) nameValue[i]).setValue(v));
        }
        return new org.apache.olingo.commons.api.edm.provider.CsdlAnnotation().setTerm(CAPABILITIES + "." + term)
                .setExpression(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlRecord()
                        .setType(CAPABILITIES + "." + term + "Type").setPropertyValues(values));
    }

    /**
     * What a client may do with an entity set: read-only, countable, searchable, filterable, sortable, expandable
     * (up to 3 levels, $levels up to 8), paging with $top/$skip. Lets OData explorers and code generators hide
     * write operations and offer the supported options.
     */
    private List<org.apache.olingo.commons.api.edm.provider.CsdlAnnotation> entitySetCapabilities(QueryDefinition q) {
        List<org.apache.olingo.commons.api.edm.provider.CsdlAnnotation> a = new ArrayList<>();
        if (q != null && q.description() != null && !q.description().isBlank()) a.add(description(q.description()));
        a.add(record("InsertRestrictions", "Insertable", false));
        a.add(record("UpdateRestrictions", "Updatable", false));
        a.add(record("DeleteRestrictions", "Deletable", false));
        a.add(record("CountRestrictions", "Countable", true));
        a.add(record("SearchRestrictions", "Searchable", true));
        a.add(record("FilterRestrictions", "Filterable", true));
        a.add(record("SortRestrictions", "Sortable", true));
        a.add(record("ExpandRestrictions", "Expandable", q != null && !visibleRelations(q).isEmpty(), "MaxLevels", 8));
        // delta links (Prefer: odata.track-changes); with row numbers as keys every shift would count as a change
        a.add(record("ChangeTracking", "Supported", q != null && q.keyMode() == at.jdbcodata.query.KeyMode.COLUMNS));
        a.add(tag(CAPABILITIES, "TopSupported", true));
        a.add(tag(CAPABILITIES, "SkipSupported", true));
        a.add(tag(CAPABILITIES, "IndexableByKey", true));
        List<org.apache.olingo.commons.api.edm.provider.annotation.CsdlExpression> transformations = new ArrayList<>();
        for (String t : List.of("aggregate", "groupby", "concat", "identity", "filter", "search", "orderby", "top", "skip",
                "compute", "expand", "topcount", "topsum", "toppercent", "bottomcount", "bottomsum", "bottompercent",
                "join", "outerjoin", "nest", "ancestors", "descendants", "traverse")) {
            transformations.add(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression(
                    org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression.ConstantExpressionType.String, t));
        }
        a.add(new org.apache.olingo.commons.api.edm.provider.CsdlAnnotation().setTerm(AGGREGATION + ".ApplySupported")
                .setExpression(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlRecord()
                        .setType(AGGREGATION + ".ApplySupportedType").setPropertyValues(List.of(
                                new org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue().setProperty("Transformations")
                                        .setValue(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlCollection().setItems(transformations)),
                                new org.apache.olingo.commons.api.edm.provider.annotation.CsdlPropertyValue().setProperty("Rollup")
                                        .setValue(new org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression(
                                                org.apache.olingo.commons.api.edm.provider.annotation.CsdlConstantExpression.ConstantExpressionType.EnumMember,
                                                AGGREGATION + ".RollupType/MultipleHierarchies"))))));
        return a;
    }

    // --- parametrised queries as functions ----------------------------------------------------------------------

    /** {@code GetUmsatz(p_jahr=2025)}: a query with parameters is also published as composable function. */
    public static final String FUNCTION_PREFIX = "Get";
    private static final java.util.regex.Pattern IDENTIFIER = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
    /** Up to this many optional parameters every combination becomes an overload; above only none and all. */
    private static final int MAX_OPTIONAL_COMBINATIONS = 4;

    /** The query behind a function name, or {@code null} when the name is no published function. */
    public QueryDefinition functionQuery(String functionName) {
        if (functionName == null || !functionName.startsWith(FUNCTION_PREFIX)) return null;
        QueryDefinition q = queries.get(functionName.substring(FUNCTION_PREFIX.length()));
        if (q == null || q.parameters().isEmpty() || queries.containsKey(functionName)) return null;
        for (QueryParameter p : q.parameters()) {
            if (!IDENTIFIER.matcher(p.name()).matches()) return null;
        }
        return q;
    }

    @Override
    public List<CsdlFunction> getFunctions(FullQualifiedName functionName) {
        if (!NAMESPACE.equals(functionName.getNamespace())) return null;
        QueryDefinition q = functionQuery(functionName.getName());
        if (q == null) return null;
        List<QueryParameter> required = q.parameters().stream().filter(QueryParameter::required).toList();
        List<QueryParameter> optional = q.parameters().stream().filter(p -> !p.required()).toList();
        List<List<QueryParameter>> variants = new ArrayList<>();
        if (optional.size() <= MAX_OPTIONAL_COMBINATIONS) {
            for (int mask = 0; mask < (1 << optional.size()); mask++) {
                List<QueryParameter> v = new ArrayList<>(required);
                for (int i = 0; i < optional.size(); i++) if ((mask & (1 << i)) != 0) v.add(optional.get(i));
                variants.add(v);
            }
        } else {
            variants.add(required);
            variants.add(q.parameters());
        }
        List<CsdlFunction> functions = new ArrayList<>();
        for (List<QueryParameter> variant : variants) {
            List<CsdlParameter> params = new ArrayList<>();
            for (QueryParameter p : q.parameters()) {
                if (!variant.contains(p)) continue; // keep the declared order
                CsdlParameter param = new CsdlParameter().setName(p.name()).setType(parameterType(p.type())).setNullable(true);
                String text = (p.description() == null ? "" : p.description())
                        + (p.defaultValue() == null || p.defaultValue().isBlank() ? "" : (p.description() == null || p.description().isBlank() ? "" : " ") + "(Default: " + p.defaultValue() + ")");
                if (!text.isBlank()) param.setAnnotations(List.of(description(text.strip())));
                params.add(param);
            }
            functions.add(new CsdlFunction().setName(functionName.getName()).setBound(false).setComposable(true)
                    .setParameters(params)
                    .setReturnType(new CsdlReturnType().setType(typeName(q.name())).setCollection(true)));
        }
        return functions;
    }

    @Override
    public CsdlFunctionImport getFunctionImport(FullQualifiedName entityContainer, String functionImportName) {
        if (!CONTAINER.equals(entityContainer) || functionQuery(functionImportName) == null) return null;
        QueryDefinition q = functionQuery(functionImportName);
        return new CsdlFunctionImport().setName(functionImportName)
                .setFunction(new FullQualifiedName(NAMESPACE, functionImportName))
                .setEntitySet(q.name()).setIncludeInServiceDocument(true);
    }

    static FullQualifiedName parameterType(ParameterType type) {
        return switch (type) {
            case STRING -> EdmPrimitiveTypeKind.String.getFullQualifiedName();
            case INT -> EdmPrimitiveTypeKind.Int64.getFullQualifiedName();
            case DECIMAL -> EdmPrimitiveTypeKind.Decimal.getFullQualifiedName();
            case DATE -> EdmPrimitiveTypeKind.Date.getFullQualifiedName();
            case TIMESTAMP -> EdmPrimitiveTypeKind.DateTimeOffset.getFullQualifiedName();
            case BOOLEAN -> EdmPrimitiveTypeKind.Boolean.getFullQualifiedName();
        };
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
            types.add(annotatedType(q));
        }
        List<CsdlFunction> functions = new ArrayList<>();
        for (String name : queries.keySet()) {
            List<CsdlFunction> f = getFunctions(new FullQualifiedName(NAMESPACE, FUNCTION_PREFIX + name));
            if (f != null) functions.addAll(f);
        }
        CsdlSchema schema = new CsdlSchema().setNamespace(NAMESPACE).setEntityTypes(types).setFunctions(functions)
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
