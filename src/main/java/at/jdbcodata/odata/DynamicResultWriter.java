package at.jdbcodata.odata;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import org.apache.olingo.server.api.uri.queryoption.SelectItem;
import org.apache.olingo.server.api.uri.queryoption.SelectOption;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * OData JSON (minimal metadata) for results Olingo's serializer cannot write: rows with dynamic properties from
 * {@code $apply}/{@code $compute}, expanded collections transformed by a nested {@code $apply}, {@code $all} and
 * {@code $crossjoin}. Writes {@code @odata.id} for entities that still have one, inline navigations (with nested
 * {@code $select} and {@code @odata.count}) and an {@code @odata.nextLink}.
 */
final class DynamicResultWriter {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private DynamicResultWriter() {
    }

    /** {@code odata.metadata} level of the response. */
    enum Metadata { NONE, MINIMAL, FULL }

    /**
     * Format parameters of the response content type: metadata level and {@code IEEE754Compatible=true} (Int64 and
     * Decimal values as JSON strings, as Olingo's serializer writes them).
     */
    record Format(Metadata metadata, boolean ieee754) {
        static final Format MINIMAL = new Format(Metadata.MINIMAL, false);

        static Format of(org.apache.olingo.commons.api.format.ContentType contentType) {
            if (contentType == null) return MINIMAL;
            String m = contentType.getParameter("odata.metadata");
            Metadata level = "full".equalsIgnoreCase(m) ? Metadata.FULL : "none".equalsIgnoreCase(m) ? Metadata.NONE : Metadata.MINIMAL;
            return new Format(level, "true".equalsIgnoreCase(contentType.getParameter("IEEE754Compatible")));
        }

        /** Content type of the response body. */
        String contentType() {
            return "application/json;odata.metadata=" + metadata.name().toLowerCase(java.util.Locale.ROOT)
                    + (ieee754 ? ";IEEE754Compatible=true" : "");
        }
    }

    /** Request-scoped writer state: the format of the current response. */
    private static final ThreadLocal<Format> FORMAT = ThreadLocal.withInitial(() -> Format.MINIMAL);

    /** Names selected by a {@code $select} option; empty = all. */
    static Set<String> selected(SelectOption select) {
        Set<String> names = new HashSet<>();
        if (select == null) return names;
        for (SelectItem item : select.getSelectItems()) {
            if (item.isStar()) return new HashSet<>();
            if (item.getResourcePath() != null && !item.getResourcePath().getUriResourceParts().isEmpty()) {
                names.add(item.getResourcePath().getUriResourceParts().get(0).getSegmentValue());
            }
        }
        return names;
    }

    /** Context URL select list: the delivered properties plus {@code Nav()} for expanded navigations. */
    static String contextSelectList(List<Entity> rows, Set<String> select, ExpandOption expand) {
        Set<String> columns = columns(rows, select);
        List<String> parts = new ArrayList<>(columns);
        if (expand != null) {
            for (ExpandItem item : expand.getExpandItems()) {
                String name = navName(item);
                if (name != null) parts.add(name + "()");
            }
        }
        return String.join(",", parts);
    }

    static byte[] collection(String context, List<Entity> rows, Integer count, String nextLink, Set<String> select,
                             ExpandOption expand, Function<Entity, String> type) {
        return collection(context, rows, count, nextLink, select, expand, type, Format.MINIMAL);
    }

    static byte[] collection(String context, List<Entity> rows, Integer count, String nextLink, Set<String> select,
                             ExpandOption expand, Function<Entity, String> type, Format format) {
        FORMAT.set(format);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            if (format.metadata() != Metadata.NONE) g.writeStringProperty("@odata.context", context);
            if (count != null) writeCount(g, "@odata.count", count);
            g.writeName("value");
            g.writeStartArray();
            Set<String> columns = columns(rows, select);
            for (Entity e : rows) writeEntity(g, e, columns, expand, type, Map.of());
            g.writeEndArray();
            if (nextLink != null) g.writeStringProperty("@odata.nextLink", nextLink);
            g.writeEndObject();
        }
        return out.toByteArray();
    }

    /**
     * {@code $crossjoin(A,B)}: one object per combination; each entity set appears as
     * {@code "A@odata.navigationLink"} or, when listed in {@code $expand}, inline (with nested {@code $select}).
     */
    static byte[] crossjoin(List<String> sets, List<Entity> rows, Integer count, String nextLink, Set<String> select,
                            ExpandOption expand) {
        FORMAT.set(Format.MINIMAL);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("@odata.context", "$metadata#Collection(Edm.ComplexType)");
            if (count != null) g.writeNumberProperty("@odata.count", count);
            g.writeName("value");
            g.writeStartArray();
            for (Entity row : rows) {
                g.writeStartObject();
                for (String set : sets) {
                    if (!select.isEmpty() && !select.contains(set)) continue;
                    Entity e = row.getNavigationLink(set).getInlineEntity();
                    ExpandItem item = null;
                    if (expand != null) {
                        for (ExpandItem i : expand.getExpandItems()) if (i.isStar() || set.equals(navName(i))) item = i;
                    }
                    if (item == null) {
                        g.writeStringProperty(set + "@odata.navigationLink", e.getId() == null ? set : e.getId().toString());
                    } else {
                        g.writeName(set);
                        writeEntity(g, e, columns(List.of(e), selected(item.getSelectOption())), null, null, Map.of());
                    }
                }
                g.writeEndObject();
            }
            g.writeEndArray();
            if (nextLink != null) g.writeStringProperty("@odata.nextLink", nextLink);
            g.writeEndObject();
        }
        return out.toByteArray();
    }

    static byte[] entity(String context, Entity e, Set<String> select, ExpandOption expand) {
        return entity(context, e, select, expand, Format.MINIMAL);
    }

    static byte[] entity(String context, Entity e, Set<String> select, ExpandOption expand, Format format) {
        FORMAT.set(format);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            if (format.metadata() != Metadata.NONE) g.writeStringProperty("@odata.context", context);
            writeBody(g, e, columns(List.of(e), select), expand, null, Map.of());
            g.writeEndObject();
        }
        return out.toByteArray();
    }

    private static Set<String> columns(List<Entity> rows, Set<String> select) {
        Set<String> columns = new LinkedHashSet<>();
        for (Entity e : rows) {
            for (Property p : e.getProperties()) {
                if (select == null || select.isEmpty() || select.contains(p.getName())) columns.add(p.getName());
            }
            // aliases of join/nest and results of the expand transformation
            for (Link l : e.getNavigationLinks()) {
                if (l instanceof ApplyLink && (select == null || select.isEmpty() || select.contains(l.getTitle()))) {
                    columns.add(l.getTitle());
                }
            }
        }
        return columns;
    }

    private static void writeEntity(JsonGenerator g, Entity e, Set<String> columns, ExpandOption expand,
                                    Function<Entity, String> type, Map<String, ExpandItem> inherited) {
        g.writeStartObject();
        writeBody(g, e, columns, expand, type, inherited);
        g.writeEndObject();
    }

    /**
     * Properties, then the expanded navigations. {@code inherited} carries the items of {@code $levels} expansions
     * down the recursion: below the first level the same navigation is expanded again with the same options.
     */
    private static void writeBody(JsonGenerator g, Entity e, Set<String> columns, ExpandOption expand,
                                  Function<Entity, String> type, Map<String, ExpandItem> inherited) {
        Format format = FORMAT.get();
        boolean full = format.metadata() == Metadata.FULL;
        if (format.metadata() != Metadata.NONE) {
            if (type != null) g.writeStringProperty("@odata.type", type.apply(e));
            else if (full && e.getType() != null) g.writeStringProperty("@odata.type", "#" + e.getType());
            if (e.getId() != null) g.writeStringProperty("@odata.id", e.getId().toString());
        }
        for (Property p : e.getProperties()) {
            if (!columns.contains(p.getName())) continue;
            String edmType = edmType(p);
            if (full && edmType != null && p.getValue() != null && !IMPLIED_TYPES.contains(edmType)) {
                g.writeStringProperty(p.getName() + "@odata.type", "#" + edmType.substring("Edm.".length()));
            }
            g.writeName(p.getName());
            writeValue(g, p.getValue(), format.ieee754() && edmType != null
                    && (edmType.equals("Edm.Int64") || edmType.equals("Edm.Decimal")));
        }
        Map<String, ExpandItem> items = new LinkedHashMap<>(inherited);
        if (expand != null) {
            for (ExpandItem item : expand.getExpandItems()) {
                String name = navName(item);
                if (name != null) {
                    items.put(name, item);
                } else {
                    for (Link l : e.getNavigationLinks()) items.putIfAbsent(l.getTitle(), item); // $expand=*
                }
            }
        }
        for (Link l : e.getNavigationLinks()) {
            if (!(l instanceof ApplyLink) || items.containsKey(l.getTitle()) || !columns.contains(l.getTitle())) continue;
            g.writeName(l.getTitle());
            if (l.getInlineEntitySet() != null) {
                List<Entity> inline = l.getInlineEntitySet().getEntities();
                Set<String> nestedColumns = columns(inline, Set.of());
                g.writeStartArray();
                for (Entity t : inline) writeEntity(g, t, nestedColumns, null, null, Map.of());
                g.writeEndArray();
            } else if (l.getInlineEntity() != null) {
                writeEntity(g, l.getInlineEntity(), columns(List.of(l.getInlineEntity()), Set.of()), null, null, Map.of());
            } else {
                g.writeNull();
            }
        }
        for (Map.Entry<String, ExpandItem> entry : items.entrySet()) {
            String n = entry.getKey();
            ExpandItem item = entry.getValue();
            Link link = e.getNavigationLink(n);
            if (item.hasCountPath()) {
                // $expand=Nav/$count: only the number of related entities
                Integer c = link == null || link.getInlineEntitySet() == null ? Integer.valueOf(0) : link.getInlineEntitySet().getCount();
                writeCount(g, n + "@odata.count", c == null ? link.getInlineEntitySet().getEntities().size() : c);
                continue;
            }
            if (item.isRef()) {
                // $expand=Nav/$ref: references instead of entities
                g.writeName(n);
                if (link != null && link.getInlineEntitySet() != null) {
                    g.writeStartArray();
                    for (Entity t : link.getInlineEntitySet().getEntities()) writeReference(g, t);
                    g.writeEndArray();
                } else if (link != null && link.getInlineEntity() != null) {
                    writeReference(g, link.getInlineEntity());
                } else if (e.getNavigationLink(n) == null && isCollectionGuess(item)) {
                    g.writeStartArray();
                    g.writeEndArray();
                } else {
                    g.writeNull();
                }
                continue;
            }
            if (link == null && inherited.containsKey(n) && !(expand != null && expand.getExpandItems().contains(item))) {
                continue; // last $levels level: the recursion ends here
            }
            Set<String> nestedSelect = selected(item.getSelectOption());
            Map<String, ExpandItem> childInherited = item.getLevelsOption() != null ? Map.of(n, item) : Map.of();
            if (link != null && link.getInlineEntitySet() != null) {
                List<Entity> inline = link.getInlineEntitySet().getEntities();
                if (item.getCountOption() != null && item.getCountOption().getValue()) {
                    Integer c = link.getInlineEntitySet().getCount();
                    writeCount(g, n + "@odata.count", c == null ? inline.size() : c);
                }
                g.writeName(n);
                g.writeStartArray();
                Set<String> nestedColumns = columns(inline, nestedSelect);
                for (Entity t : inline) writeEntity(g, t, nestedColumns, item.getExpandOption(), null, childInherited);
                g.writeEndArray();
            } else if (link != null && link.getInlineEntity() != null) {
                g.writeName(n);
                writeEntity(g, link.getInlineEntity(), columns(List.of(link.getInlineEntity()), nestedSelect),
                        item.getExpandOption(), null, childInherited);
            } else {
                g.writeName(n);
                g.writeNull();
            }
        }
    }

    private static void writeReference(JsonGenerator g, Entity t) {
        g.writeStartObject();
        if (t.getId() != null) g.writeStringProperty("@odata.id", t.getId().toString());
        g.writeEndObject();
    }

    /** An expand item without inline data: collection navigations get an empty array, to-one ones null. */
    private static boolean isCollectionGuess(ExpandItem item) {
        List<org.apache.olingo.server.api.uri.UriResource> parts = item.getResourcePath() == null ? List.of()
                : item.getResourcePath().getUriResourceParts();
        return !parts.isEmpty() && parts.get(0) instanceof org.apache.olingo.server.api.uri.UriResourceNavigation nav
                && nav.isCollection();
    }

    private static void writeCount(JsonGenerator g, String name, long count) {
        if (FORMAT.get().ieee754()) g.writeStringProperty(name, String.valueOf(count));
        else g.writeNumberProperty(name, count);
    }

    /** Types JSON clients infer without a type annotation (full metadata leaves them out, like Olingo). */
    private static final Set<String> IMPLIED_TYPES = Set.of("Edm.String", "Edm.Boolean", "Edm.Int32", "Edm.Double");

    /** The EDM type of a property: declared (query columns) or derived from the value (computed, aggregated). */
    private static String edmType(Property p) {
        if (p.getType() != null && p.getType().startsWith("Edm.")) return p.getType();
        Object v = p.getValue();
        if (v == null) return null;
        Object c = FilterValues.canonical(v);
        if (c instanceof BigDecimal || c instanceof BigInteger) return "Edm.Decimal";
        if (c instanceof Boolean) return "Edm.Boolean";
        if (c instanceof java.time.LocalDate) return "Edm.Date";
        if (c instanceof java.time.Instant || c instanceof java.time.OffsetDateTime) return "Edm.DateTimeOffset";
        if (c instanceof java.time.LocalTime) return "Edm.TimeOfDay";
        return "Edm.String";
    }

    static String navName(ExpandItem item) {
        if (item.isStar() || item.getResourcePath() == null || item.getResourcePath().getUriResourceParts().isEmpty()) {
            return null;
        }
        return item.getResourcePath().getUriResourceParts().get(0).getSegmentValue();
    }

    private static void writeValue(JsonGenerator g, Object raw) {
        writeValue(g, raw, false);
    }

    private static void writeValue(JsonGenerator g, Object raw, boolean asString) {
        Object v = raw == null ? null : FilterValues.canonical(raw);
        if (v == null) {
            g.writeNull();
        } else if (asString && (v instanceof BigDecimal || v instanceof BigInteger || v instanceof Long || v instanceof Integer)) {
            g.writeString(v instanceof BigDecimal bd ? bd.toPlainString() : v.toString());
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
        } else {
            g.writeString(v.toString());
        }
    }
}
