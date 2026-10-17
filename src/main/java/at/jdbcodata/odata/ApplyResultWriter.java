package at.jdbcodata.odata;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.temporal.TemporalAccessor;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * JSON for {@code $apply} results. Aggregated rows are dynamic structures that the entity type does not declare,
 * which Olingo's serializer would drop, so they are written here in OData JSON (minimal metadata):
 * {@code {"@odata.context":"$metadata#Umsatz(Region,Summe)","@odata.count":4,"value":[{"Region":"Nord","Summe":…}]}}.
 */
final class ApplyResultWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ApplyResultWriter() {
    }

    static byte[] write(String entitySetName, List<Entity> rows, Integer count, Set<String> select) {
        Set<String> columns = new LinkedHashSet<>();
        for (Entity e : rows) {
            for (Property p : e.getProperties()) {
                if (select == null || select.isEmpty() || select.contains(p.getName())) columns.add(p.getName());
            }
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("@odata.context", "$metadata#" + entitySetName + "(" + String.join(",", columns) + ")");
            if (count != null) g.writeNumberProperty("@odata.count", count);
            g.writeName("value");
            g.writeStartArray();
            for (Entity e : rows) {
                g.writeStartObject();
                for (Property p : e.getProperties()) {
                    if (!columns.contains(p.getName())) continue;
                    g.writeName(p.getName());
                    writeValue(g, p.getValue());
                }
                g.writeEndObject();
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        return out.toByteArray();
    }

    private static void writeValue(JsonGenerator g, Object raw) {
        Object v = raw == null ? null : FilterValues.canonical(raw);
        if (v == null) {
            g.writeNull();
        } else if (v instanceof BigDecimal bd) {
            BigDecimal plain = bd.stripTrailingZeros();
            if (plain.scale() <= 0 && plain.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
                g.writeNumber(plain.toBigInteger());
            } else {
                g.writeNumber(bd);
            }
        } else if (v instanceof BigInteger bi) {
            g.writeNumber(bi);
        } else if (v instanceof Number n) {
            g.writeNumber(n.doubleValue());
        } else if (v instanceof Boolean b) {
            g.writeBoolean(b);
        } else if (v instanceof TemporalAccessor) {
            g.writeString(v.toString());
        } else {
            g.writeString(v.toString());
        }
    }
}
