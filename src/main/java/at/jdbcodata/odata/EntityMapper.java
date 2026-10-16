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
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;

/** Turns a {@link ResultTable} into Olingo entities using the persisted schema snapshot. */
final class EntityMapper {

    private record Slot(QueryColumn column, int resultIndex, EdmPrimitiveTypeKind kind) {
    }

    private EntityMapper() {
    }

    static EntityCollection toCollection(QueryDefinition q, ResultTable table, int skip, Integer top) {
        List<Slot> slots = new ArrayList<>();
        for (QueryColumn c : q.columns()) {
            if (!c.visible()) continue;
            slots.add(new Slot(c, table.indexOf(c.name()), EdmPrimitiveTypeKind.valueOfFQN(c.edmType())));
        }
        boolean rowIdKey = q.keyMode() != KeyMode.COLUMNS || q.columns().stream().noneMatch(QueryColumn::key);

        EntityCollection collection = new EntityCollection();
        int total = table.rows().size();
        int from = Math.min(Math.max(skip, 0), total);
        int to = top == null ? total : Math.min(total, from + Math.max(top, 0));
        for (int r = from; r < to; r++) {
            Object[] row = table.rows().get(r);
            Entity e = new Entity();
            e.setType(DynamicEdmProvider.typeName(q.name()).getFullQualifiedNameAsString());
            long rowId = r + 1L;
            if (rowIdKey) {
                e.addProperty(new Property(null, DynamicEdmProvider.ROW_ID, ValueType.PRIMITIVE, rowId));
            }
            StringBuilder key = new StringBuilder();
            for (Slot s : slots) {
                Object value = s.resultIndex() < 0 ? null : convert(row[s.resultIndex()], s);
                e.addProperty(new Property(null, s.column().propertyName(), ValueType.PRIMITIVE, value));
                if (!rowIdKey && s.column().key()) {
                    if (key.length() > 0) key.append(',');
                    key.append(s.column().propertyName()).append('=').append(keyLiteral(value));
                }
            }
            e.setId(URI.create(q.name() + "(" + (rowIdKey ? String.valueOf(rowId) : key) + ")"));
            collection.getEntities().add(e);
        }
        collection.setCount(total);
        return collection;
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
