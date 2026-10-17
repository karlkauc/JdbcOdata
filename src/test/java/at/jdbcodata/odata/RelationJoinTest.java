package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.QueryRelation;
import java.math.BigDecimal;
import java.util.List;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.junit.jupiter.api.Test;

class RelationJoinTest {

    private static Entity entity(Object... nameValue) {
        Entity e = new Entity();
        for (int i = 0; i < nameValue.length; i += 2) {
            e.addProperty(new Property(null, (String) nameValue[i], ValueType.PRIMITIVE, nameValue[i + 1]));
        }
        return e;
    }

    @Test
    void matchesAcrossNumericTypesAndCompositeKeys() {
        QueryRelation r = new QueryRelation("Order", "Orders", List.of("cust", "year"), List.of("customerId", "year"),
                true, null, null);
        Entity a = entity("customerId", 7L, "year", 2025);
        Entity b = entity("customerId", new BigDecimal("7.00"), "year", 2025L);
        Entity other = entity("customerId", 8L, "year", 2025);
        Entity incomplete = entity("customerId", null, "year", 2025);
        RelationJoin join = new RelationJoin(r, List.of(a, b, other, incomplete));

        assertThat(join.matches(entity("cust", 7, "year", new BigDecimal("2025")))).containsExactly(a, b);
        assertThat(join.matches(entity("cust", 9, "year", 2025))).isEmpty();
        assertThat(join.matches(entity("cust", null, "year", 2025))).as("null never matches").isEmpty();
        assertThat(join.matches(entity("year", 2025))).as("missing column").isEmpty();
    }

    @Test
    void stringsMatchExactly() {
        QueryRelation r = new QueryRelation("C", "Customers", List.of("name"), List.of("name"), false, null, null);
        Entity alpha = entity("name", "Alpha");
        RelationJoin join = new RelationJoin(r, List.of(alpha, entity("name", "alpha")));
        assertThat(join.matches(entity("name", "Alpha"))).containsExactly(alpha);
    }
}
