package at.jdbcodata.odata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.EdmEnumType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitor;
import org.apache.olingo.server.api.uri.queryoption.expression.Literal;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.api.uri.queryoption.expression.MethodKind;
import org.apache.olingo.server.api.uri.queryoption.expression.UnaryOperatorKind;

/** Evaluates an OData expression against one entity. Values are canonicalised via {@link FilterValues}. */
final class EntityExpressionVisitor implements ExpressionVisitor<Object> {

    private static final Locale LOCALE = Locale.ENGLISH;
    private final Entity entity;

    EntityExpressionVisitor(Entity entity) {
        this.entity = entity;
    }

    @Override
    public Object visitBinaryOperator(BinaryOperatorKind op, Object left, Object right)
            throws ExpressionVisitException, ODataApplicationException {
        return switch (op) {
            case AND -> bool(left) && bool(right);
            case OR -> bool(left) || bool(right);
            case EQ -> FilterValues.equal(left, right);
            case NE -> !FilterValues.equal(left, right);
            case GT -> ordered(left, right) > 0;
            case GE -> ordered(left, right) >= 0;
            case LT -> ordered(left, right) < 0;
            case LE -> ordered(left, right) <= 0;
            case ADD -> arithmetic(left, right, op);
            case SUB -> arithmetic(left, right, op);
            case MUL -> arithmetic(left, right, op);
            case DIV -> arithmetic(left, right, op);
            case MOD -> arithmetic(left, right, op);
            default -> throw unsupported("Operator " + op);
        };
    }

    public Object visitBinaryOperator(BinaryOperatorKind op, Object left, List<Object> right)
            throws ExpressionVisitException, ODataApplicationException {
        if (op != BinaryOperatorKind.IN) throw unsupported("Operator " + op);
        for (Object candidate : right) {
            if (FilterValues.equal(left, candidate)) return true;
        }
        return false;
    }

    @Override
    public Object visitUnaryOperator(UnaryOperatorKind op, Object operand)
            throws ExpressionVisitException, ODataApplicationException {
        return switch (op) {
            case NOT -> !bool(operand);
            case MINUS -> operand == null ? null : number(operand).negate();
        };
    }

    @Override
    public Object visitMethodCall(MethodKind method, List<Object> p)
            throws ExpressionVisitException, ODataApplicationException {
        switch (method) {
            case CONTAINS, STARTSWITH, ENDSWITH, INDEXOF, CONCAT: {
                String a = string(p.get(0));
                String b = string(p.get(1));
                if (method == MethodKind.CONCAT) return (a == null ? "" : a) + (b == null ? "" : b);
                if (a == null || b == null) return method == MethodKind.INDEXOF ? null : false;
                return switch (method) {
                    case CONTAINS -> a.contains(b);
                    case STARTSWITH -> a.startsWith(b);
                    case ENDSWITH -> a.endsWith(b);
                    default -> BigDecimal.valueOf(a.indexOf(b));
                };
            }
            case LENGTH: {
                String a = string(p.get(0));
                return a == null ? null : BigDecimal.valueOf(a.length());
            }
            case TOLOWER: {
                String a = string(p.get(0));
                return a == null ? null : a.toLowerCase(LOCALE);
            }
            case TOUPPER: {
                String a = string(p.get(0));
                return a == null ? null : a.toUpperCase(LOCALE);
            }
            case TRIM: {
                String a = string(p.get(0));
                return a == null ? null : a.strip();
            }
            case SUBSTRING: {
                String a = string(p.get(0));
                if (a == null) return null;
                int start = Math.max(0, Math.min(a.length(), number(p.get(1)).intValue()));
                if (p.size() > 2) {
                    int len = Math.max(0, number(p.get(2)).intValue());
                    return a.substring(start, Math.min(a.length(), start + len));
                }
                return a.substring(start);
            }
            case YEAR, MONTH, DAY, HOUR, MINUTE, SECOND: {
                Object v = p.get(0);
                if (v == null) return null;
                ZonedDateTime z = FilterValues.zoned(v);
                if (z == null && v instanceof LocalTime t) z = t.atDate(LocalDate.EPOCH).atZone(java.time.ZoneId.systemDefault());
                if (z == null) throw bad("Function " + method.name().toLowerCase() + " needs a date or time value");
                return BigDecimal.valueOf(switch (method) {
                    case YEAR -> z.getYear();
                    case MONTH -> z.getMonthValue();
                    case DAY -> z.getDayOfMonth();
                    case HOUR -> z.getHour();
                    case MINUTE -> z.getMinute();
                    default -> z.getSecond();
                });
            }
            case DATE: {
                Object v = p.get(0);
                if (v == null) return null;
                if (v instanceof LocalDate d) return d;
                ZonedDateTime z = FilterValues.zoned(v);
                if (z == null) throw bad("Function date needs a date/time value");
                return z.toLocalDate();
            }
            case TIME: {
                Object v = p.get(0);
                if (v == null) return null;
                if (v instanceof LocalTime t) return t;
                ZonedDateTime z = FilterValues.zoned(v);
                if (z == null) throw bad("Function time needs a date/time value");
                return z.toLocalTime();
            }
            case NOW:
                return Instant.now();
            case ROUND:
                return p.get(0) == null ? null : number(p.get(0)).setScale(0, RoundingMode.HALF_UP);
            case FLOOR:
                return p.get(0) == null ? null : number(p.get(0)).setScale(0, RoundingMode.FLOOR);
            case CEILING:
                return p.get(0) == null ? null : number(p.get(0)).setScale(0, RoundingMode.CEILING);
            default:
                throw unsupported("Function " + method.name().toLowerCase());
        }
    }

    @Override
    public Object visitLiteral(Literal literal) throws ExpressionVisitException, ODataApplicationException {
        String text = literal.getText();
        EdmType type = literal.getType();
        if (text == null || "null".equals(text) || type == null) return null;
        EdmPrimitiveTypeKind kind;
        try {
            kind = EdmPrimitiveTypeKind.valueOfFQN(type.getFullQualifiedName());
        } catch (IllegalArgumentException e) {
            throw unsupported("Literal type " + type.getFullQualifiedName());
        }
        try {
            return switch (kind) {
                case String -> unquote(text);
                case Boolean -> Boolean.parseBoolean(text);
                case Date -> LocalDate.parse(text);
                case DateTimeOffset -> OffsetDateTime.parse(text).toInstant();
                case TimeOfDay -> LocalTime.parse(text);
                case Guid -> text.toLowerCase(LOCALE);
                case Byte, SByte, Int16, Int32, Int64, Decimal, Double, Single -> new BigDecimal(text);
                default -> throw unsupported("Literal type " + kind);
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw bad("Invalid literal '" + text + "'");
        }
    }

    @Override
    public Object visitMember(Member member) throws ExpressionVisitException, ODataApplicationException {
        List<UriResource> parts = member.getResourcePath().getUriResourceParts();
        if (parts.size() != 1 || !(parts.get(0) instanceof UriResourcePrimitiveProperty prop)) {
            throw unsupported("Only direct property access is supported in $filter and $orderby");
        }
        Property property = entity.getProperty(prop.getProperty().getName());
        return property == null ? null : FilterValues.canonical(property.getValue());
    }

    @Override
    public Object visitAlias(String aliasName) throws ExpressionVisitException, ODataApplicationException {
        throw unsupported("Parameter aliases");
    }

    @Override
    public Object visitTypeLiteral(EdmType type) throws ExpressionVisitException, ODataApplicationException {
        throw unsupported("Type literals");
    }

    @Override
    public Object visitLambdaReference(String variableName) throws ExpressionVisitException, ODataApplicationException {
        throw unsupported("Lambda expressions");
    }

    @Override
    public Object visitLambdaExpression(String lambdaFunction, String lambdaVariable, Expression expression)
            throws ExpressionVisitException, ODataApplicationException {
        throw unsupported("Lambda expressions");
    }

    @Override
    public Object visitEnum(EdmEnumType type, List<String> enumValues)
            throws ExpressionVisitException, ODataApplicationException {
        throw unsupported("Enum literals");
    }

    private static boolean bool(Object v) throws ODataApplicationException {
        if (v instanceof Boolean b) return b;
        if (v == null) return false;
        throw bad("Expected a boolean expression");
    }

    private static int ordered(Object a, Object b) throws ODataApplicationException {
        if (a == null || b == null) return Integer.MIN_VALUE; // comparisons with null are false
        Integer c = FilterValues.compare(a, b);
        if (c == null) throw bad("Cannot compare " + a.getClass().getSimpleName() + " with " + b.getClass().getSimpleName());
        return c;
    }

    private static Object arithmetic(Object left, Object right, BinaryOperatorKind op) throws ODataApplicationException {
        if (left == null || right == null) return null;
        BigDecimal a = number(left);
        BigDecimal b = number(right);
        return switch (op) {
            case ADD -> a.add(b);
            case SUB -> a.subtract(b);
            case MUL -> a.multiply(b);
            case DIV -> b.signum() == 0 ? null : a.divide(b, 10, RoundingMode.HALF_UP).stripTrailingZeros();
            default -> b.signum() == 0 ? null : a.remainder(b);
        };
    }

    private static BigDecimal number(Object v) throws ODataApplicationException {
        if (v instanceof BigDecimal bd) return bd;
        throw bad("Expected a number");
    }

    private static String string(Object v) throws ODataApplicationException {
        if (v == null) return null;
        if (v instanceof String s) return s;
        if (v instanceof BigDecimal bd) return bd.toPlainString();
        return v.toString();
    }

    private static String unquote(String text) {
        if (text.length() >= 2 && text.startsWith("'") && text.endsWith("'")) {
            return text.substring(1, text.length() - 1).replace("''", "'");
        }
        return text;
    }

    private static ODataApplicationException bad(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
    }

    private static ODataApplicationException unsupported(String what) {
        return new ODataApplicationException(what + " not supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
    }
}
