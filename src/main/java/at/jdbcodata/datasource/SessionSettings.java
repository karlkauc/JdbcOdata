package at.jdbcodata.datasource;

import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.SqlDialect;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Session settings applied right after connecting, e.g. Oracle NLS parameters ({@code NLS_LANG} is an OCI
 * environment variable and not read by the thin driver). Only known keys per dialect are allowed and values are
 * restricted, because they end up in a generated statement: {@code ALTER SESSION SET}, {@code set_config} or
 * {@code SET SESSION}. A datasource defines defaults, a query may override single keys.
 * <p>
 * {@code NLS_COMP} is deliberately missing: with {@code LINGUISTIC} the push-down comparisons would no longer be
 * binary, see {@link SqlDialect#binary}.
 */
public final class SessionSettings {

    private static final List<String> ORACLE = List.of("NLS_LANGUAGE", "NLS_TERRITORY", "NLS_SORT", "NLS_DATE_FORMAT",
            "NLS_DATE_LANGUAGE", "NLS_TIMESTAMP_FORMAT", "NLS_TIMESTAMP_TZ_FORMAT", "NLS_NUMERIC_CHARACTERS",
            "NLS_CURRENCY", "NLS_ISO_CURRENCY", "NLS_DUAL_CURRENCY", "NLS_CALENDAR", "TIME_ZONE");
    private static final List<String> POSTGRES = List.of("DateStyle", "IntervalStyle", "TimeZone", "lc_monetary",
            "lc_numeric", "lc_time", "search_path", "extra_float_digits");
    private static final List<String> MYSQL = List.of("time_zone", "lc_time_names", "sql_mode", "collation_connection",
            "div_precision_increment");
    private static final List<String> H2 = List.of("TIME_ZONE");

    /** No quote, semicolon or backslash: values are embedded as string literals. */
    private static final Pattern VALUE = Pattern.compile("[\\p{L}\\p{N} _.,:/+\\-€$£¥\"]{1,100}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d{1,9}");

    private SessionSettings() {
    }

    /** Allowed keys for a JDBC URL, in canonical spelling; empty when the database is not supported. */
    public static List<String> allowedKeys(String jdbcUrl) {
        SqlDialect dialect = SqlDialect.of(jdbcUrl);
        if (dialect == null) return List.of();
        return switch (dialect) {
            case ORACLE -> ORACLE;
            case POSTGRES -> POSTGRES;
            case MYSQL -> MYSQL;
            case STANDARD -> H2;
        };
    }

    /**
     * Checks keys and values against the database of {@code jdbcUrl} and returns them with canonical key spelling.
     *
     * @throws InvalidRequestException unknown key, invalid value or a database without session settings
     */
    public static Map<String, String> validate(String jdbcUrl, Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) return Map.of();
        List<String> allowed = allowedKeys(jdbcUrl);
        if (allowed.isEmpty()) {
            throw new InvalidRequestException("Session settings are not supported for this JDBC URL");
        }
        Map<String, String> result = new LinkedHashMap<>();
        settings.forEach((key, value) -> {
            String k = key == null ? "" : key.strip();
            String canonical = allowed.stream().filter(a -> a.equalsIgnoreCase(k)).findFirst()
                    .orElseThrow(() -> new InvalidRequestException("Session setting '" + k + "' is not allowed. Allowed: "
                            + String.join(", ", allowed)));
            String v = value == null ? "" : value.strip();
            if (!VALUE.matcher(v).matches()) {
                throw new InvalidRequestException("Session setting " + canonical
                        + ": value must be 1-100 letters, digits, blanks or . , : / + - \" (no quotes or semicolons)");
            }
            // pgjdbc parses dates itself and closes the connection unless DateStyle starts with ISO
            if (canonical.equals("DateStyle") && !v.toUpperCase(java.util.Locale.ROOT).matches("ISO\\s*(,.*)?")) {
                throw new InvalidRequestException("Session setting DateStyle must start with ISO (e.g. 'ISO, DMY'); "
                        + "the PostgreSQL JDBC driver requires it");
            }
            if (result.put(canonical, v) != null) {
                throw new InvalidRequestException("Session setting " + canonical + " is set twice");
            }
        });
        return Map.copyOf(result);
    }

    /** Datasource defaults overridden by the query, sorted by key. */
    public static Map<String, String> effective(Datasource ds, QueryDefinition query) {
        Map<String, String> merged = new TreeMap<>(ds.sessionSettings());
        if (query != null) merged.putAll(query.sessionSettings());
        return merged;
    }

    /** The single statement that applies the settings, or {@code null} when there are none. */
    public static String initSql(String jdbcUrl, Map<String, String> settings) {
        if (settings == null || settings.isEmpty()) return null;
        SqlDialect dialect = SqlDialect.of(jdbcUrl);
        if (dialect == null) throw new InvalidRequestException("Session settings are not supported for this JDBC URL");
        Map<String, String> sorted = new TreeMap<>(settings);
        return switch (dialect) {
            case ORACLE -> "ALTER SESSION SET " + sorted.entrySet().stream()
                    .map(e -> e.getKey() + "=" + literal(e.getValue())).collect(Collectors.joining(" "));
            case POSTGRES -> "SELECT " + sorted.entrySet().stream()
                    .map(e -> "set_config(" + literal(e.getKey()) + ", " + literal(e.getValue()) + ", false)")
                    .collect(Collectors.joining(", "));
            case MYSQL -> "SET SESSION " + sorted.entrySet().stream()
                    .map(e -> e.getKey() + "=" + (NUMBER.matcher(e.getValue()).matches() ? e.getValue() : literal(e.getValue())))
                    .collect(Collectors.joining(", "));
            case STANDARD -> "SET TIME ZONE " + literal(sorted.get("TIME_ZONE"));
        };
    }

    /** {@code KEY=VALUE} lines, as the admin UI shows them. */
    public static String toLines(Map<String, String> map) {
        if (map == null || map.isEmpty()) return "";
        return map.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"));
    }

    /** Parses {@code KEY=VALUE} lines; blank lines and lines starting with {@code #} are skipped. */
    public static Map<String, String> fromLines(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        if (text == null) return result;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) throw new InvalidRequestException("Expected KEY=VALUE, got '" + line + "'");
            String key = line.substring(0, eq).strip();
            if (result.put(key, line.substring(eq + 1).strip()) != null) {
                throw new InvalidRequestException("'" + key + "' is set twice");
            }
        }
        return result;
    }

    private static String literal(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
