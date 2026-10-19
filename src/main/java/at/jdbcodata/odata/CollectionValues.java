package at.jdbcodata.odata;

import at.jdbcodata.query.CollectionShape;
import at.jdbcodata.query.ComplexField;
import at.jdbcodata.query.QueryColumn;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.olingo.commons.api.data.ComplexValue;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;

/**
 * Collection-valued properties in the feed: building them from column values, and elements as rows for
 * expressions ({@code Tags/any(t:t eq 'Neu')}, {@code $select=Positionen($filter=Betrag gt 100)}).
 */
final class CollectionValues {

    /** Property of the row that stands for a primitive element ({@code $this}, a lambda variable). */
    static final String THIS = "jdbcodata_this";

    private CollectionValues() {
    }

    /** The property for column {@code c} with the raw result value. */
    static Property property(QueryColumn c, String complexType, Object raw) {
        return switch (c.collection()) {
            case PRIMITIVE -> new Property(c.elementType(), c.propertyName(), ValueType.COLLECTION_PRIMITIVE,
                    primitives(raw, EdmPrimitiveTypeKind.valueOfFQN(c.elementType())));
            case COMPLEX -> new Property(complexType, c.propertyName(), ValueType.COLLECTION_COMPLEX, complex(raw, c.structure()));
            case NONE -> new Property(c.edmType(), c.propertyName(), ValueType.PRIMITIVE,
                    raw instanceof List<?> l ? CollectionShape.literal(l) : raw);
        };
    }

    /** Elements converted to the element type; null for a null or unreadable value. */
    static List<Object> primitives(Object raw, EdmPrimitiveTypeKind kind) {
        List<Object> list = CollectionShape.asList(raw);
        if (list == null) return null;
        List<Object> out = new ArrayList<>(list.size());
        for (Object v : list) out.add(CollectionShape.convert(v, kind));
        return out;
    }

    static List<ComplexValue> complex(Object raw, List<ComplexField> fields) {
        List<Object> list = CollectionShape.asList(raw);
        if (list == null) return null;
        List<ComplexValue> out = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof Map<?, ?> m)) {
                out.add(null);
                continue;
            }
            ComplexValue cv = new ComplexValue();
            for (ComplexField f : fields) {
                Object v = m.get(f.key());
                EdmPrimitiveTypeKind kind = EdmPrimitiveTypeKind.valueOfFQN(f.edmType());
                cv.getValue().add(f.collection()
                        ? new Property(f.edmType(), f.name(), ValueType.COLLECTION_PRIMITIVE, v == null ? null : primitives(v, kind))
                        : new Property(f.edmType(), f.name(), ValueType.PRIMITIVE, CollectionShape.convert(v, kind)));
            }
            out.add(cv);
        }
        return out;
    }

    /** The elements of a collection property ({@code List} of values or of {@link ComplexValue}), never null. */
    static List<?> elements(Property p) {
        return p == null || !(p.getValue() instanceof List<?> l) ? List.of() : l;
    }

    /**
     * An element as row: a complex value with its fields as properties, a primitive value as property
     * {@link #THIS} (so {@code $this} and lambda variables of primitive collections resolve to it).
     */
    static Entity row(Object element) {
        Entity e = new Entity();
        if (element instanceof ComplexValue cv) {
            for (Property p : cv.getValue()) e.addProperty(p);
        } else {
            e.addProperty(new Property(null, THIS, ValueType.PRIMITIVE, element));
        }
        return e;
    }

    /** Text of a value for $search and fingerprints: lists and complex values with all their elements. */
    static String text(Object v) {
        if (v instanceof List<?> l) {
            StringBuilder out = new StringBuilder("[");
            for (Object e : l) out.append(text(e)).append('\u0001');
            return out.append(']').toString();
        }
        if (v instanceof ComplexValue cv) {
            StringBuilder out = new StringBuilder("{");
            for (Property p : cv.getValue()) out.append(p.getName()).append('=').append(text(p.getValue())).append('\u0001');
            return out.append('}').toString();
        }
        Object c = FilterValues.canonical(v);
        if (c instanceof java.math.BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        return String.valueOf(c);
    }
}
