package at.jdbcodata.query;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Values of collection columns: SQL arrays (read as lists), JSON arrays (as text) and, from older cache entries,
 * PostgreSQL array literals ({@code {a,b}}). Detects at snapshot time what a column can be published as (element
 * type, structure of JSON objects) and converts the values for the feed.
 */
public final class CollectionShape {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern DATE_TIME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:\\d{2})");

    private CollectionShape() {
    }

    /** What the snapshot found: element type of a value collection, or fields of an object collection. */
    public record Detected(String elementType, List<ComplexField> structure, boolean singleObject) {
        static final Detected NOTHING = new Detected(null, List.of(), false);

        Detected(String elementType, List<ComplexField> structure) {
            this(elementType, structure, false);
        }
    }

    /**
     * Inspects the sample values of one column. SQL arrays always yield an element type (from the type name, else
     * from the values); text and JSON columns only when every non-null sample is a JSON array: of objects
     * (structure) or of values (element type).
     */
    static Detected detect(ResultColumn column, List<Object> samples) {
        boolean array = column.jdbcType() == java.sql.Types.ARRAY;
        if (!array && column.mapping().kind() != EdmPrimitiveTypeKind.String) return Detected.NOTHING;
        if (!array) {
            Detected objects = objects(samples);
            if (objects != null) return objects;
        }
        List<Object> elements = new ArrayList<>();
        boolean any = false;
        for (Object s : samples) {
            if (s == null) continue;
            List<Object> list = array ? asList(s) : jsonArray(s);
            if (list == null) return Detected.NOTHING;
            any = true;
            elements.addAll(list);
        }
        if (array) {
            if (elements.stream().anyMatch(e -> e instanceof List)) return Detected.NOTHING; // multi-dimensional
            EdmPrimitiveTypeKind named = TypeMapper.arrayElementType(column.jdbcTypeName());
            EdmPrimitiveTypeKind kind = named != null && named != EdmPrimitiveTypeKind.String ? named : kindOf(elements);
            return new Detected(fqn(kind), List.of());
        }
        if (!any || elements.stream().allMatch(e -> e == null)) return Detected.NOTHING;
        if (elements.stream().allMatch(e -> e == null || e instanceof Map)) {
            return new Detected(null, structure(elements));
        }
        if (elements.stream().noneMatch(e -> e instanceof Map || e instanceof List)) {
            return new Detected(fqn(kindOf(elements)), List.of());
        }
        return Detected.NOTHING;
    }

    /** Every non-null sample a JSON object: their fields as structure of one complex value; else null. */
    private static Detected objects(List<Object> samples) {
        List<Object> objects = new ArrayList<>();
        for (Object s : samples) {
            if (s == null) continue;
            Object o = jsonObject(s);
            if (o == null) return null;
            objects.add(o);
        }
        if (objects.isEmpty()) return null;
        List<ComplexField> fields = structure(objects);
        return fields.isEmpty() ? null : new Detected(null, fields, true);
    }

    /** A JSON object as map, or null. */
    public static Map<String, Object> jsonObject(Object raw) {
        if (raw == null) return null;
        if (raw instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) m;
            return map;
        }
        String text = raw.toString().strip();
        if (!text.startsWith("{")) return null;
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isObject()) return null;
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) value(node);
            return map;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static List<ComplexField> structure(List<Object> objects) {
        Map<String, List<Object>> values = new LinkedHashMap<>();
        for (Object o : objects) {
            if (!(o instanceof Map<?, ?> m)) continue;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                values.computeIfAbsent(String.valueOf(e.getKey()), k -> new ArrayList<>()).add(e.getValue());
            }
        }
        List<ComplexField> fields = new ArrayList<>();
        Set<String> used = new HashSet<>();
        int i = 0;
        for (Map.Entry<String, List<Object>> e : values.entrySet()) {
            String name = TypeMapper.toPropertyName(e.getKey(), i++, used);
            List<Object> vs = e.getValue().stream().filter(v -> v != null).toList();
            if (!vs.isEmpty() && vs.stream().allMatch(v -> v instanceof List<?> l && l.stream().noneMatch(x -> x instanceof Map || x instanceof List))) {
                List<Object> inner = new ArrayList<>();
                for (Object v : vs) inner.addAll((List<?>) v);
                fields.add(new ComplexField(e.getKey(), name, fqn(kindOf(inner)), true));
            } else if (vs.stream().anyMatch(v -> v instanceof Map || v instanceof List)) {
                fields.add(new ComplexField(e.getKey(), name, fqn(EdmPrimitiveTypeKind.String), false)); // nested: JSON text
            } else {
                fields.add(new ComplexField(e.getKey(), name, fqn(kindOf(vs)), false));
            }
        }
        return fields;
    }

    /** The narrowest primitive type all non-null values fit; String when they disagree or there are none. */
    static EdmPrimitiveTypeKind kindOf(Collection<Object> values) {
        EdmPrimitiveTypeKind kind = null;
        for (Object v : values) {
            if (v == null) continue;
            EdmPrimitiveTypeKind k = kindOfValue(v);
            if (kind == null) kind = k;
            else if (kind != k) kind = widen(kind, k);
            if (kind == EdmPrimitiveTypeKind.String) return kind;
        }
        return kind == null ? EdmPrimitiveTypeKind.String : kind;
    }

    private static EdmPrimitiveTypeKind kindOfValue(Object v) {
        return switch (v) {
            case Integer i -> EdmPrimitiveTypeKind.Int32;
            case Long l -> EdmPrimitiveTypeKind.Int64;
            case Double d -> EdmPrimitiveTypeKind.Double;
            case BigDecimal bd -> integral(bd);
            case BigInteger bi -> integral(new BigDecimal(bi));
            case Boolean b -> EdmPrimitiveTypeKind.Boolean;
            case java.sql.Date d -> EdmPrimitiveTypeKind.Date;
            case Timestamp t -> EdmPrimitiveTypeKind.DateTimeOffset;
            case Time t -> EdmPrimitiveTypeKind.TimeOfDay;
            case UUID u -> EdmPrimitiveTypeKind.Guid;
            case String s -> DATE.matcher(s).matches() ? EdmPrimitiveTypeKind.Date
                    : DATE_TIME.matcher(s).matches() ? EdmPrimitiveTypeKind.DateTimeOffset : EdmPrimitiveTypeKind.String;
            default -> EdmPrimitiveTypeKind.String;
        };
    }

    /** JSON numbers: whole numbers as Int32/Int64 (from samples, so a later larger value would not fit: Int64). */
    private static EdmPrimitiveTypeKind integral(BigDecimal bd) {
        BigDecimal n = bd.stripTrailingZeros();
        if (n.scale() > 0) return EdmPrimitiveTypeKind.Decimal;
        return n.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0 ? EdmPrimitiveTypeKind.Int64 : EdmPrimitiveTypeKind.Decimal;
    }

    private static EdmPrimitiveTypeKind widen(EdmPrimitiveTypeKind a, EdmPrimitiveTypeKind b) {
        Set<EdmPrimitiveTypeKind> numbers = Set.of(EdmPrimitiveTypeKind.Int32, EdmPrimitiveTypeKind.Int64,
                EdmPrimitiveTypeKind.Decimal, EdmPrimitiveTypeKind.Double);
        if (numbers.contains(a) && numbers.contains(b)) {
            if (a == EdmPrimitiveTypeKind.Double || b == EdmPrimitiveTypeKind.Double) return EdmPrimitiveTypeKind.Double;
            if (a == EdmPrimitiveTypeKind.Decimal || b == EdmPrimitiveTypeKind.Decimal) return EdmPrimitiveTypeKind.Decimal;
            return EdmPrimitiveTypeKind.Int64;
        }
        if (Set.of(a, b).equals(Set.of(EdmPrimitiveTypeKind.Date, EdmPrimitiveTypeKind.DateTimeOffset))) {
            return EdmPrimitiveTypeKind.String;
        }
        return EdmPrimitiveTypeKind.String;
    }

    private static String fqn(EdmPrimitiveTypeKind kind) {
        return kind.getFullQualifiedName().getFullQualifiedNameAsString();
    }

    // --- values ------------------------------------------------------------------------------------------------

    /**
     * The elements of a collection value: a list (SQL array), JSON array text or a PostgreSQL array literal;
     * {@code null} when the value is none of these. JSON objects become maps, numbers BigDecimal.
     */
    public static List<Object> asList(Object raw) {
        if (raw == null) return null;
        if (raw instanceof List<?> l) return new ArrayList<>(l);
        if (raw instanceof Object[] a) return new ArrayList<>(List.of(a));
        String text = raw.toString().strip();
        if (text.startsWith("[")) return jsonArray(text);
        if (text.startsWith("{")) return arrayLiteral(text);
        return null;
    }

    private static List<Object> jsonArray(Object raw) {
        String text = raw.toString().strip();
        if (!text.startsWith("[")) return null;
        try {
            JsonNode node = JSON.readTree(text);
            if (node == null || !node.isArray()) return null;
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value(node);
            return list;
        } catch (JacksonException e) {
            return null;
        }
    }

    private static Object value(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) return null;
        if (node.isArray()) {
            List<Object> out = new ArrayList<>();
            for (JsonNode n : node) out.add(value(n));
            return out;
        }
        if (node.isObject()) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : node.properties()) out.put(e.getKey(), value(e.getValue()));
            return out;
        }
        if (node.isBoolean()) return node.booleanValue();
        if (node.isNumber()) return node.decimalValue();
        return node.asString();
    }

    /** {@code {a,"b c",NULL}}: one-dimensional PostgreSQL array literal (as older cache entries hold it). */
    private static List<Object> arrayLiteral(String text) {
        if (!text.endsWith("}")) return null;
        List<Object> out = new ArrayList<>();
        String body = text.substring(1, text.length() - 1);
        if (body.isEmpty()) return out;
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (quoted) {
                if (c == '\\' && i + 1 < body.length()) current.append(body.charAt(++i));
                else if (c == '"') quoted = false;
                else current.append(c);
            } else if (c == '"') {
                quoted = true;
                wasQuoted = true;
            } else if (c == '{') {
                return null; // nested
            } else if (c == ',') {
                out.add(!wasQuoted && current.toString().strip().equalsIgnoreCase("NULL") ? null : current.toString());
                current.setLength(0);
                wasQuoted = false;
            } else {
                current.append(c);
            }
        }
        out.add(!wasQuoted && current.toString().strip().equalsIgnoreCase("NULL") ? null : current.toString());
        return out;
    }

    /**
     * One element converted to the Java type the feed uses for {@code kind} (Integer, Long, BigDecimal, Double,
     * Boolean, String, java.sql.Date/Time/Timestamp, UUID); {@code null} when it does not fit.
     */
    public static Object convert(Object v, EdmPrimitiveTypeKind kind) {
        if (v == null) return null;
        try {
            return switch (kind) {
                case String -> v instanceof Map || v instanceof List ? json(v) : v instanceof BigDecimal bd ? bd.toPlainString() : v.toString();
                case Int32, Int16, Byte, SByte -> v instanceof Number n ? Integer.valueOf(new BigDecimal(n.toString()).intValueExact())
                        : Integer.valueOf(v.toString().strip());
                case Int64 -> v instanceof Number n ? Long.valueOf(new BigDecimal(n.toString()).longValueExact())
                        : Long.valueOf(v.toString().strip());
                case Decimal -> scaled(v instanceof BigDecimal bd ? bd : new BigDecimal(v.toString().strip()));
                case Double, Single -> v instanceof Number n ? Double.valueOf(n.doubleValue()) : Double.valueOf(v.toString().strip());
                case Boolean -> v instanceof Boolean b ? b : switch (v.toString().strip().toLowerCase(java.util.Locale.ROOT)) {
                    case "true", "t" -> Boolean.TRUE;
                    case "false", "f" -> Boolean.FALSE;
                    default -> null;
                };
                case Date -> v instanceof java.sql.Date d ? d : java.sql.Date.valueOf(LocalDate.parse(v.toString().strip()));
                case DateTimeOffset -> v instanceof Timestamp t ? t : Timestamp.from(instant(v.toString().strip()));
                case TimeOfDay -> v instanceof Time t ? t : Time.valueOf(LocalTime.parse(v.toString().strip()));
                case Guid -> v instanceof UUID u ? u : UUID.fromString(v.toString().strip());
                default -> v.toString();
            };
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Decimal elements are published as Decimal(38,10): more decimals are rounded. */
    private static BigDecimal scaled(BigDecimal bd) {
        if (bd.scale() > TypeMapper.DEFAULT_DECIMAL_SCALE) return bd.setScale(TypeMapper.DEFAULT_DECIMAL_SCALE, java.math.RoundingMode.HALF_UP);
        return bd.scale() < 0 ? bd.setScale(0) : bd;
    }

    private static Instant instant(String s) {
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (java.time.format.DateTimeParseException e) {
            return Timestamp.valueOf(s.replace('T', ' ')).toInstant(); // without offset (PostgreSQL timestamp literal)
        }
    }

    private static String json(Object v) {
        return JSON.writeValueAsString(v);
    }

    /**
     * A list shown as plain text, as the column appeared before collections existed: PostgreSQL array literal
     * ({@code {a,"b c",NULL}}).
     */
    public static String literal(List<?> list) {
        StringBuilder out = new StringBuilder("{");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) out.append(',');
            Object v = list.get(i);
            if (v == null) {
                out.append("NULL");
            } else if (v instanceof List<?> nested) {
                out.append(literal(nested));
            } else if (v instanceof Boolean b) {
                out.append(b ? 't' : 'f');
            } else {
                String s = v instanceof BigDecimal bd ? bd.toPlainString() : v.toString();
                boolean quote = s.isEmpty() || s.equalsIgnoreCase("NULL") || s.chars().anyMatch(c -> "{},\"\\".indexOf(c) >= 0 || Character.isWhitespace(c));
                if (quote) out.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                else out.append(s);
            }
        }
        return out.append('}').toString();
    }
}
