package at.jdbcodata.odata;

import at.jdbcodata.query.KeyMode;
import at.jdbcodata.query.QueryColumn;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.ResultTable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;

/** Turns a {@link ResultTable} into Olingo entities using the persisted schema snapshot. */
final class EntityMapper {

    private record Slot(QueryColumn column, int resultIndex, EdmPrimitiveTypeKind kind) {
    }

    private EntityMapper() {
    }

    /** All rows as entities, in result order. Query options are applied afterwards by {@link QueryOptionsEvaluator}. */
    static List<Entity> toEntities(QueryDefinition q, ResultTable table) {
        List<Slot> slots = new ArrayList<>();
        for (QueryColumn c : q.columns()) {
            if (!c.visible()) continue;
            slots.add(new Slot(c, table.indexOf(c.name()), EdmPrimitiveTypeKind.valueOfFQN(c.edmType())));
        }
        boolean rowIdKey = q.keyMode() != KeyMode.COLUMNS || q.columns().stream().noneMatch(QueryColumn::key);

        List<Entity> entities = new ArrayList<>(table.rows().size());
        for (int r = 0; r < table.rows().size(); r++) {
            Object[] row = table.rows().get(r);
            Entity e = new Entity();
            e.setType(DynamicEdmProvider.typeName(q.name()).getFullQualifiedNameAsString());
            long rowId = r + 1L;
            if (rowIdKey) {
                e.addProperty(new Property("Edm.Int64", DynamicEdmProvider.ROW_ID, ValueType.PRIMITIVE, rowId));
            }
            StringBuilder key = new StringBuilder();
            for (Slot s : slots) {
                Object raw = s.resultIndex() < 0 ? null : row[s.resultIndex()];
                if (s.column().collectionValued() || raw instanceof List) {
                    e.addProperty(CollectionValues.property(s.column(), complexType(q, s.column()), raw));
                    String copy = DynamicEdmProvider.textCopyName(q, s.column());
                    if (copy != null) e.addProperty(new Property("Edm.String", copy, ValueType.PRIMITIVE, CollectionValues.rawText(raw)));
                    continue; // never a key
                }
                Object value = convert(raw, s);
                e.addProperty(new Property(s.column().edmType(), s.column().propertyName(), ValueType.PRIMITIVE, value));
                if (!rowIdKey && s.column().key()) {
                    if (key.length() > 0) key.append(',');
                    key.append(s.column().propertyName()).append('=').append(keyLiteral(value));
                }
            }
            e.setId(entityId(q.name() + "(" + (rowIdKey ? String.valueOf(rowId) : key) + ")"));
            entities.add(e);
        }
        return entities;
    }

    /** Relative entity id; characters such as spaces in string keys ({@code Customers(name='Beta AG')}) are encoded. */
    static URI entityId(String path) {
        try {
            return new URI(null, null, path, null);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Cannot build entity id for " + path, e);
        }
    }

    /** Type name of the elements of a complex collection column (see {@link DynamicEdmProvider#complexTypeName}). */
    private static String complexType(QueryDefinition q, QueryColumn c) {
        return DynamicEdmProvider.NAMESPACE + "." + q.name() + "_" + c.propertyName();
    }

    private static Object convert(Object value, Slot s) {
        if (value == null) return null;
        if (s.kind() == EdmPrimitiveTypeKind.Decimal && value instanceof BigDecimal bd) {
            Integer scale = s.column().scale();
            if (scale != null && bd.scale() > scale) {
                return bd.setScale(scale, RoundingMode.HALF_UP);
            }
            return bd;
        }
        return value;
    }

    private static String keyLiteral(Object value) {
        if (value == null) return "null";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
