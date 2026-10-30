package at.jdbcodata.query;

import at.jdbcodata.common.InvalidRequestException;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Converts, resolves and binds query parameters. Values are only ever bound, never concatenated into SQL. */
public final class ParameterBinder {

    public record BoundValue(ParameterType type, Object value) {
    }

    private ParameterBinder() {
    }

    /** Applies defaults and required checks; keys are the declared parameter names. */
    public static Map<String, Object> resolve(QueryDefinition query, Map<String, String> provided) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (QueryParameter p : query.parameters()) {
            String raw = provided.get(p.name());
            if (raw == null || raw.isBlank()) {
                raw = p.defaultValue();
            }
            if (raw == null || raw.isBlank()) {
                if (p.required()) {
                    throw new InvalidRequestException("Missing required parameter '" + p.name()
                            + "'. Pass it as query option, e.g. ?" + p.name() + "=value");
                }
                values.put(p.name(), null);
            } else {
                values.put(p.name(), convert(p, raw));
            }
        }
        return values;
    }

    public static Object convert(QueryParameter p, String raw) {
        String v = raw.strip();
        try {
            return switch (p.type()) {
                case STRING -> v;
                case INT -> Long.valueOf(v);
                case DECIMAL -> new BigDecimal(v);
                case BOOLEAN -> parseBoolean(p.name(), v);
                case DATE -> Date.valueOf(LocalDate.parse(v));
                case TIMESTAMP -> parseTimestamp(v);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            // not the value itself: it ends up in the execution log and the parameter may be sensitive
            throw new InvalidRequestException("Parameter '" + p.name() + "' expects " + p.type());
        }
    }

    /** Orders the resolved values by their occurrence in the SQL text. */
    public static List<BoundValue> order(NamedParameterSql parsed, QueryDefinition query, Map<String, Object> values) {
        Map<String, ParameterType> types = new LinkedHashMap<>();
        query.parameters().forEach(p -> types.put(p.name(), p.type()));
        List<BoundValue> ordered = new ArrayList<>(parsed.parameterNames().size());
        for (String name : parsed.parameterNames()) {
            ParameterType type = types.get(name);
            if (type == null) {
                throw new InvalidRequestException("SQL references undeclared parameter ':" + name + "'");
            }
            ordered.add(new BoundValue(type, values.get(name)));
        }
        return ordered;
    }

    public static void bind(PreparedStatement ps, List<BoundValue> ordered) throws SQLException {
        int i = 1;
        for (BoundValue b : ordered) {
            if (b.value() == null) {
                ps.setNull(i, sqlType(b.type()));
            } else {
                switch (b.type()) {
                    case STRING -> ps.setString(i, (String) b.value());
                    case INT -> ps.setLong(i, (Long) b.value());
                    case DECIMAL -> ps.setBigDecimal(i, (BigDecimal) b.value());
                    case BOOLEAN -> ps.setBoolean(i, (Boolean) b.value());
                    case DATE -> ps.setDate(i, (Date) b.value());
                    case TIMESTAMP -> ps.setTimestamp(i, (Timestamp) b.value());
                }
            }
            i++;
        }
    }

    private static int sqlType(ParameterType t) {
        return switch (t) {
            case STRING -> Types.VARCHAR;
            case INT -> Types.BIGINT;
            case DECIMAL -> Types.NUMERIC;
            case BOOLEAN -> Types.BOOLEAN;
            case DATE -> Types.DATE;
            case TIMESTAMP -> Types.TIMESTAMP;
        };
    }

    private static Boolean parseBoolean(String name, String v) {
        return switch (v.toLowerCase()) {
            case "true", "1", "yes", "y", "ja", "j" -> Boolean.TRUE;
            case "false", "0", "no", "n", "nein" -> Boolean.FALSE;
            default -> throw new InvalidRequestException("Parameter '" + name + "' expects BOOLEAN, got '" + v + "'");
        };
    }

    private static Timestamp parseTimestamp(String v) {
        try {
            return Timestamp.from(OffsetDateTime.parse(v).toInstant());
        } catch (DateTimeParseException ignored) {
            // no offset given: interpret in server time zone
        }
        String normalized = v.contains("T") ? v : v.replace(' ', 'T');
        return Timestamp.valueOf(LocalDateTime.parse(normalized));
    }
}
