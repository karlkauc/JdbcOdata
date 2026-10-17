package at.jdbcodata.odata;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.edm.EdmAnnotation;
import org.apache.olingo.commons.api.edm.EdmElement;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmKeyPropertyRef;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.edm.EdmReferentialConstraint;
import org.apache.olingo.commons.api.edm.EdmStructuredType;
import org.apache.olingo.commons.api.edm.EdmTerm;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.constants.EdmTypeKind;
import org.apache.olingo.commons.core.edm.primitivetype.EdmPrimitiveTypeFactory;
import org.apache.olingo.server.core.uri.queryoption.apply.DynamicProperty;

/**
 * The type of intermediate rows (after {@code join}, {@code nest}, {@code groupby}, …) for Olingo's parsers: the
 * declared properties of the start type plus every property and inline navigation the rows actually carry. Built
 * from the rows at runtime, so expressions that follow a transformation can refer to its aliases
 * ({@code join(Umsaetze as U)/filter(U/Betrag gt 100)}).
 */
final class RowType implements EdmEntityType {

    private static int counter;

    private final EdmStructuredType start;
    private final Map<String, EdmProperty> properties = new LinkedHashMap<>();
    private final Map<String, EdmNavigationProperty> navigations = new LinkedHashMap<>();
    private final String name;

    private RowType(EdmStructuredType start) {
        this.start = start;
        synchronized (RowType.class) {
            this.name = "Row" + (++counter);
        }
    }

    /** The type of {@code rows}; {@code start} (may be null) contributes its declared properties and navigations. */
    static EdmStructuredType of(List<Entity> rows, EdmStructuredType start, Edm edm) {
        RowType t = new RowType(start);
        Map<String, List<Entity>> nestedRows = new LinkedHashMap<>();
        Map<String, Boolean> nestedCollection = new LinkedHashMap<>();
        Map<String, String> nestedTypes = new LinkedHashMap<>();
        for (Entity e : rows) {
            for (Property p : e.getProperties()) {
                if (t.properties.containsKey(p.getName())) {
                    if (t.properties.get(p.getName()).getType() == null && p.getValue() != null) {
                        t.properties.put(p.getName(), new DynamicProperty(p.getName(), primitive(p)));
                    }
                    continue;
                }
                EdmProperty declared = start == null ? null : start.getStructuralProperty(p.getName());
                t.properties.put(p.getName(), declared != null ? declared : new DynamicProperty(p.getName(), primitive(p)));
            }
            for (Link l : e.getNavigationLinks()) {
                if (start != null && start.getNavigationProperty(l.getTitle()) != null) continue;
                List<Entity> inner = nestedRows.computeIfAbsent(l.getTitle(), k -> new ArrayList<>());
                if (l.getInlineEntitySet() != null) {
                    nestedCollection.put(l.getTitle(), true);
                    inner.addAll(l.getInlineEntitySet().getEntities());
                } else {
                    nestedCollection.putIfAbsent(l.getTitle(), false);
                    if (l.getInlineEntity() != null) inner.add(l.getInlineEntity());
                }
                for (Entity i : inner) if (i.getType() != null) nestedTypes.putIfAbsent(l.getTitle(), i.getType());
            }
        }
        for (Map.Entry<String, List<Entity>> n : nestedRows.entrySet()) {
            String typeName = nestedTypes.get(n.getKey());
            EdmEntityType declared = typeName == null || edm == null ? null : edm.getEntityType(new FullQualifiedName(typeName));
            EdmStructuredType inner = of(n.getValue(), declared, edm);
            t.navigations.put(n.getKey(), new Navigation(n.getKey(), (EdmEntityType) inner, nestedCollection.get(n.getKey())));
        }
        return t;
    }

    /** {@code base} plus navigation {@code name} (if missing, e.g. a join alias on an empty result). */
    static EdmStructuredType withNavigation(EdmStructuredType base, String name, EdmEntityType target, boolean collection) {
        if (base.getNavigationProperty(name) != null) return base;
        RowType t = new RowType(base);
        t.navigations.put(name, new Navigation(name, target, collection));
        return t;
    }

    private static EdmType primitive(Property p) {
        if (p.getType() != null && p.getType().startsWith("Edm.")) {
            try {
                return EdmPrimitiveTypeFactory.getInstance(EdmPrimitiveTypeKind.valueOfFQN(p.getType()));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        Object v = p.getValue() == null ? null : FilterValues.canonical(p.getValue());
        EdmPrimitiveTypeKind kind = v instanceof BigDecimal ? EdmPrimitiveTypeKind.Decimal
                : v instanceof Boolean ? EdmPrimitiveTypeKind.Boolean
                : v instanceof LocalDate ? EdmPrimitiveTypeKind.Date
                : v instanceof Instant ? EdmPrimitiveTypeKind.DateTimeOffset
                : v instanceof LocalTime ? EdmPrimitiveTypeKind.TimeOfDay
                : v instanceof String ? EdmPrimitiveTypeKind.String : null;
        return kind == null ? null : EdmPrimitiveTypeFactory.getInstance(kind);
    }

    /** A dynamic navigation: an alias of {@code join}/{@code nest} or an inline expansion. */
    private record Navigation(String name, EdmEntityType type, boolean collection) implements EdmNavigationProperty {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public EdmEntityType getType() {
            return type;
        }

        @Override
        public boolean isCollection() {
            return collection;
        }

        @Override
        public boolean isNullable() {
            return true;
        }

        @Override
        public boolean containsTarget() {
            return false;
        }

        @Override
        public EdmNavigationProperty getPartner() {
            return null;
        }

        @Override
        public String getReferencingPropertyName(String referencedPropertyName) {
            return null;
        }

        @Override
        public org.apache.olingo.commons.api.edm.EdmOnDelete getOnDelete() {
            return null;
        }

        @Override
        public List<EdmReferentialConstraint> getReferentialConstraints() {
            return List.of();
        }

        @Override
        public EdmAnnotation getAnnotation(EdmTerm term, String qualifier) {
            return null;
        }

        @Override
        public List<EdmAnnotation> getAnnotations() {
            return List.of();
        }
    }

    @Override
    public EdmElement getProperty(String n) {
        if (properties.containsKey(n)) return properties.get(n);
        if (navigations.containsKey(n)) return navigations.get(n);
        return start == null ? null : start.getProperty(n);
    }

    @Override
    public List<String> getPropertyNames() {
        List<String> names = new ArrayList<>(properties.keySet());
        if (start != null) for (String n : start.getPropertyNames()) if (!names.contains(n)) names.add(n);
        return names;
    }

    @Override
    public EdmProperty getStructuralProperty(String n) {
        if (properties.containsKey(n)) return properties.get(n);
        return start == null ? null : start.getStructuralProperty(n);
    }

    @Override
    public EdmNavigationProperty getNavigationProperty(String n) {
        if (navigations.containsKey(n)) return navigations.get(n);
        return start == null ? null : start.getNavigationProperty(n);
    }

    @Override
    public List<String> getNavigationPropertyNames() {
        List<String> names = new ArrayList<>(navigations.keySet());
        if (start != null) for (String n : start.getNavigationPropertyNames()) if (!names.contains(n)) names.add(n);
        return names;
    }

    @Override
    public String getNamespace() {
        return start != null ? start.getNamespace() : DynamicEdmProvider.NAMESPACE;
    }

    @Override
    public String getName() {
        return start != null ? start.getName() : name;
    }

    @Override
    public FullQualifiedName getFullQualifiedName() {
        return new FullQualifiedName(getNamespace(), getName());
    }

    @Override
    public EdmTypeKind getKind() {
        return EdmTypeKind.ENTITY;
    }

    @Override
    public EdmAnnotation getAnnotation(EdmTerm term, String qualifier) {
        return null;
    }

    @Override
    public List<EdmAnnotation> getAnnotations() {
        return List.of();
    }

    @Override
    public EdmEntityType getBaseType() {
        return null;
    }

    @Override
    public boolean compatibleTo(EdmType targetType) {
        return targetType == this || (start != null && start.compatibleTo(targetType));
    }

    @Override
    public boolean isOpenType() {
        return true;
    }

    @Override
    public boolean isAbstract() {
        return false;
    }

    @Override
    public List<String> getKeyPredicateNames() {
        return start instanceof EdmEntityType et ? et.getKeyPredicateNames() : List.of();
    }

    @Override
    public List<EdmKeyPropertyRef> getKeyPropertyRefs() {
        return start instanceof EdmEntityType et ? et.getKeyPropertyRefs() : List.of();
    }

    @Override
    public EdmKeyPropertyRef getKeyPropertyRef(String keyPredicateName) {
        return start instanceof EdmEntityType et ? et.getKeyPropertyRef(keyPredicateName) : null;
    }

    @Override
    public boolean hasStream() {
        return false;
    }
}
