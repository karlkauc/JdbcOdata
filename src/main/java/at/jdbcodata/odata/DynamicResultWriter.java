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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("@odata.context", context);
            if (count != null) g.writeNumberProperty("@odata.count", count);
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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (JsonGenerator g = JSON.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("@odata.context", context);
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
        if (type != null) g.writeStringProperty("@odata.type", type.apply(e));
        if (e.getId() != null) g.writeStringProperty("@odata.id", e.getId().toString());
        for (Property p : e.getProperties()) {
            if (!columns.contains(p.getName())) continue;
            g.writeName(p.getName());
            writeValue(g, p.getValue());
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
        for (Map.Entry<String, ExpandItem> entry : items.entrySet()) {
            String n = entry.getKey();
            ExpandItem item = entry.getValue();
            Link link = e.getNavigationLink(n);
            if (link == null && inherited.containsKey(n) && !(expand != null && expand.getExpandItems().contains(item))) {
                continue; // last $levels level: the recursion ends here
            }
            Set<String> nestedSelect = selected(item.getSelectOption());
            Map<String, ExpandItem> childInherited = item.getLevelsOption() != null ? Map.of(n, item) : Map.of();
            if (link != null && link.getInlineEntitySet() != null) {
                List<Entity> inline = link.getInlineEntitySet().getEntities();
                if (item.getCountOption() != null && item.getCountOption().getValue()) {
                    Integer c = link.getInlineEntitySet().getCount();
                    g.writeNumberProperty(n + "@odata.count", c == null ? inline.size() : c);
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

    static String navName(ExpandItem item) {
        if (item.isStar() || item.getResourcePath() == null || item.getResourcePath().getUriResourceParts().isEmpty()) {
            return null;
        }
        return item.getResourcePath().getUriResourceParts().get(0).getSegmentValue();
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
        } else {
            g.writeString(v.toString());
        }
    }
}
