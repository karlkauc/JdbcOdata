package at.jdbcodata.odata;

import java.math.BigDecimal;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.UUID;

/**
 * Canonical value forms for in-memory evaluation of {@code $filter} and {@code $orderby}: numbers become
 * {@link BigDecimal}, timestamps {@link Instant}, dates {@link LocalDate}, times {@link LocalTime}.
 */
final class FilterValues {

    private FilterValues() {
    }

    static Object canonical(Object v) {
        return switch (v) {
            case null -> null;
            case BigDecimal bd -> bd;
            case Integer i -> BigDecimal.valueOf(i);
            case Long l -> BigDecimal.valueOf(l);
            case Short s -> BigDecimal.valueOf(s);
            case Byte b -> BigDecimal.valueOf(b);
            case Double d -> BigDecimal.valueOf(d);
            case Float f -> BigDecimal.valueOf(f);
            case Number n -> new BigDecimal(n.toString());
            case java.sql.Date d -> d.toLocalDate();
            case Time t -> t.toLocalTime();
            case Timestamp ts -> ts.toInstant();
            case Date d -> d.toInstant();
            case ZonedDateTime z -> z.toInstant();
            case UUID u -> u.toString().toLowerCase();
            default -> v;
        };
    }

    /** Compares two canonical values; {@code null} if they are not comparable. Nulls sort first. */
    static Integer compare(Object a, Object b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        if (a instanceof LocalDate d && b instanceof Instant) a = d.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (b instanceof LocalDate d && a instanceof Instant) b = d.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) return x.compareTo(y);
        if (a instanceof String x && b instanceof String y) return x.compareTo(y);
        if (a instanceof Boolean x && b instanceof Boolean y) return x.compareTo(y);
        if (a instanceof Instant x && b instanceof Instant y) return x.compareTo(y);
        if (a instanceof LocalDate x && b instanceof LocalDate y) return x.compareTo(y);
        if (a instanceof LocalTime x && b instanceof LocalTime y) return x.compareTo(y);
        return null;
    }

    static boolean equal(Object a, Object b) {
        Integer c = compare(a, b);
        return c != null && c == 0;
    }

    static ZonedDateTime zoned(Object v) {
        if (v instanceof Instant i) return i.atZone(ZoneId.systemDefault());
        if (v instanceof LocalDate d) return d.atStartOfDay(ZoneId.systemDefault());
        return null;
    }
}
