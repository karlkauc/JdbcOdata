package at.jdbcodata.odata;

import at.jdbcodata.query.QueryRelation;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;

/**
 * In-memory join for {@code $expand}: groups the target entities by the relation's target columns and looks up the
 * source entities' values. Values are compared type-tolerant ({@code 1}, {@code 1L} and {@code 1.00} match), because
 * both sides come from different queries whose JDBC types may differ (INTEGER vs. BIGINT vs. NUMERIC).
 * A row with a {@code null} in any join column matches nothing, as in SQL.
 */
final class RelationJoin {

    private final QueryRelation relation;
    private final Map<List<Object>, List<Entity>> targetsByKey = new HashMap<>();

    RelationJoin(QueryRelation relation, List<Entity> targets) {
        this.relation = relation;
        for (Entity t : targets) {
            List<Object> key = key(t, relation.targetColumns());
            if (key != null) targetsByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
        }
    }

    /** Target entities matching the source entity, in target order; empty when none match. */
    List<Entity> matches(Entity source) {
        List<Object> key = key(source, relation.localColumns());
        if (key == null) return List.of();
        return targetsByKey.getOrDefault(key, List.of());
    }

    private static List<Object> key(Entity e, List<String> columns) {
        List<Object> key = new ArrayList<>(columns.size());
        for (String c : columns) {
            Property p = e.getProperty(c);
            Object v = p == null ? null : p.getValue();
            if (v == null) return null;
            key.add(normalize(v));
        }
        return key;
    }

    static Object normalize(Object v) {
        if (v instanceof BigDecimal bd) return bd.stripTrailingZeros().toPlainString();
        if (v instanceof Number n) {
            if (v instanceof Double || v instanceof Float) {
                return BigDecimal.valueOf(n.doubleValue()).stripTrailingZeros().toPlainString();
            }
            return BigDecimal.valueOf(n.longValue()).toPlainString();
        }
        return Objects.toString(v);
    }
}
