package at.jdbcodata.query;

import java.sql.Types;
import java.util.Set;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;

/** JDBC type → EDM primitive type mapping (ARCHITECTURE.md §4) and OData property name sanitising. */
public final class TypeMapper {

    public static final int TEMPORAL_PRECISION = 9;
    private static final int DEFAULT_DECIMAL_PRECISION = 38;
    private static final int DEFAULT_DECIMAL_SCALE = 10;

    public record EdmMapping(EdmPrimitiveTypeKind kind, Integer precision, Integer scale) {
        public String fqn() {
            return kind.getFullQualifiedName().getFullQualifiedNameAsString();
        }
    }

    private TypeMapper() {
    }

    public static EdmMapping map(int jdbcType, String typeName, int precision, int scale) {
        if ("uuid".equalsIgnoreCase(typeName)) {
            return of(EdmPrimitiveTypeKind.Guid);
        }
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR, Types.LONGNVARCHAR,
                 Types.CLOB, Types.NCLOB, Types.SQLXML -> of(EdmPrimitiveTypeKind.String);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> of(EdmPrimitiveTypeKind.Int32);
            case Types.BIGINT -> of(EdmPrimitiveTypeKind.Int64);
            case Types.NUMERIC, Types.DECIMAL -> numeric(precision, scale);
            case Types.REAL, Types.FLOAT, Types.DOUBLE -> of(EdmPrimitiveTypeKind.Double);
            case Types.DATE -> of(EdmPrimitiveTypeKind.Date);
            case Types.TIME, Types.TIME_WITH_TIMEZONE ->
                    new EdmMapping(EdmPrimitiveTypeKind.TimeOfDay, TEMPORAL_PRECISION, null);
            case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE ->
                    new EdmMapping(EdmPrimitiveTypeKind.DateTimeOffset, TEMPORAL_PRECISION, null);
            case Types.BOOLEAN, Types.BIT -> of(EdmPrimitiveTypeKind.Boolean);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> of(EdmPrimitiveTypeKind.Binary);
            default -> of(EdmPrimitiveTypeKind.String);
        };
    }

    /**
     * Integers declared as NUMERIC(p,0) become Int32/Int64 so Excel shows whole numbers. Unknown precision
     * (Oracle NUMBER, PostgreSQL numeric without constraint) becomes Decimal(38,10); values are rounded to scale.
     */
    private static EdmMapping numeric(int precision, int scale) {
        if (scale == 0 && precision > 0 && precision <= 18) {
            return of(precision <= 9 ? EdmPrimitiveTypeKind.Int32 : EdmPrimitiveTypeKind.Int64);
        }
        if (precision <= 0 || precision > DEFAULT_DECIMAL_PRECISION || scale < 0 || scale > precision) {
            return new EdmMapping(EdmPrimitiveTypeKind.Decimal, DEFAULT_DECIMAL_PRECISION, DEFAULT_DECIMAL_SCALE);
        }
        return new EdmMapping(EdmPrimitiveTypeKind.Decimal, precision, scale);
    }

    private static EdmMapping of(EdmPrimitiveTypeKind kind) {
        return new EdmMapping(kind, null, null);
    }

    /** Turns a DB column label into a unique OData simple identifier. */
    public static String toPropertyName(String label, int ordinal, Set<String> used) {
        String base = label == null ? "" : label.strip().replaceAll("[^A-Za-z0-9_]", "_");
        if (base.isEmpty()) base = "Column" + ordinal;
        if (Character.isDigit(base.charAt(0))) base = "_" + base;
        if (base.length() > 128) base = base.substring(0, 128);
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            candidate = base + "_" + suffix++;
        }
        return candidate;
    }
}
