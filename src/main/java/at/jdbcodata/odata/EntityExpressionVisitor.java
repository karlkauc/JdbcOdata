package at.jdbcodata.odata;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.EdmEnumType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceCount;
import org.apache.olingo.server.api.uri.UriResourceLambdaAll;
import org.apache.olingo.server.api.uri.UriResourceLambdaAny;
import org.apache.olingo.server.api.uri.UriResourceLambdaVariable;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.queryoption.expression.Binary;
import org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitor;
import org.apache.olingo.server.api.uri.queryoption.expression.Literal;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.api.uri.queryoption.expression.Method;
import org.apache.olingo.server.api.uri.queryoption.expression.MethodKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Unary;
import org.apache.olingo.server.api.uri.queryoption.expression.UnaryOperatorKind;

/**
 * Evaluates an OData expression against one entity. Values are canonicalised via {@link FilterValues}.
 *
 * <p>Member paths may follow navigation properties when a {@link NavigationResolver} is given: single-valued
 * navigations ({@code Kunde/Region}), chains of them, {@code any}/{@code all} lambdas over collections
 * ({@code Umsaetze/any(u: u/Betrag gt 1000)}) and {@code Umsaetze/$count}. Because Olingo's {@code Member.accept}
 * hands lambdas to the visitor without the path that leads to the collection, {@link #evaluate} walks the
 * expression tree itself instead of using {@code accept}.
 */
final class EntityExpressionVisitor implements ExpressionVisitor<Object> {

    private static final Locale LOCALE = Locale.ENGLISH;
    /** Value of a single-valued navigation that leads to an entity, so {@code Kunde eq null} / {@code ne null} work. */
    static final Object RELATED_ENTITY = new Object() {
        @Override
        public String toString() {
            return "related entity";
        }
    };
    private final Entity entity;
    private final NavigationResolver navigation;
    private final Map<String, Entity> lambdaVariables;

    EntityExpressionVisitor(Entity entity) {
        this(entity, null, Map.of());
    }

    EntityExpressionVisitor(Entity entity, NavigationResolver navigation) {
        this(entity, navigation, Map.of());
    }

    private EntityExpressionVisitor(Entity entity, NavigationResolver navigation, Map<String, Entity> lambdaVariables) {
        this.entity = entity;
        this.navigation = navigation;
        this.lambdaVariables = lambdaVariables;
    }

    /** Evaluates {@code expr}; use this instead of {@code expr.accept(visitor)} so lambdas keep their path. */
    Object evaluate(Expression expr) throws ExpressionVisitException, ODataApplicationException {
        if (expr instanceof Member m) {
            return member(m);
        }
        if (expr instanceof Binary b) {
            if (b.getOperator() == BinaryOperatorKind.IN) {
                List<Object> right = new ArrayList<>();
                for (Expression e : b.getExpressions()) right.add(evaluate(e));
                return visitBinaryOperator(b.getOperator(), evaluate(b.getLeftOperand()), right);
            }
            return visitBinaryOperator(b.getOperator(), evaluate(b.getLeftOperand()), evaluate(b.getRightOperand()));
        }
        if (expr instanceof Unary u) {
            return visitUnaryOperator(u.getOperator(), evaluate(u.getOperand()));
        }
        if (expr instanceof Method m) {
            List<Object> params = new ArrayList<>();
            for (Expression e : m.getParameters()) params.add(evaluate(e));
            return visitMethodCall(m.getMethod(), params);
        }
        return expr.accept(this);
    }

    private Object member(Member member) throws ExpressionVisitException, ODataApplicationException {
        List<UriResource> parts = member.getResourcePath().getUriResourceParts();
        Entity current = entity;
        for (int i = 0; i < parts.size(); i++) {
            UriResource part = parts.get(i);
            boolean last = i == parts.size() - 1;
            if (part instanceof UriResourceLambdaVariable v && i == 0) {
                current = lambdaVariables.get(v.getVariableName());
                if (current == null) throw bad("Unknown lambda variable '" + v.getVariableName() + "'");
                if (last) throw unsupported("Comparing whole entities");
                continue;
            }
            if (part instanceof UriResourcePrimitiveProperty prop) {
                if (!last) throw unsupported("Paths below a primitive property");
                Property property = current.getProperty(prop.getProperty().getName());
                return property == null ? null : FilterValues.canonical(property.getValue());
            }
            if (part instanceof UriResourceNavigation nav) {
                if (navigation == null) throw unsupported("Navigation in $filter and $orderby");
                List<Entity> targets = navigation.navigate(current, nav.getProperty().getName());
                if (!nav.isCollection()) {
                    if (last) return targets.isEmpty() ? null : RELATED_ENTITY; // only meaningful against null
                    if (targets.isEmpty()) return null; // like SQL: a missing related row compares as null
                    current = targets.get(0);
                    continue;
                }
                if (last) throw unsupported("Comparing collections; use any(), all() or /$count");
                UriResource next = parts.get(i + 1);
                if (next instanceof UriResourceLambdaAny any) {
                    return lambda(targets, any.getLambdaVariable(), any.getExpression(), true);
                }
                if (next instanceof UriResourceLambdaAll all) {
                    return lambda(targets, all.getLambdaVariable(), all.getExpression(), false);
                }
                if (next instanceof UriResourceCount) {
                    return BigDecimal.valueOf(targets.size());
                }
                throw unsupported("Path segment " + next.getSegmentValue() + " after a collection navigation");
            }
            throw unsupported("Path segment " + part.getSegmentValue() + " in $filter and $orderby");
        }
        throw bad("Empty member path");
    }

    /** {@code any}: true if one element matches (false for an empty collection); {@code all}: true if none fails. */
    private boolean lambda(List<Entity> elements, String variable, Expression body, boolean any)
            throws ExpressionVisitException, ODataApplicationException {
        if (body == null) return any ? !elements.isEmpty() : true; // any() without argument
        for (Entity element : elements) {
            Map<String, Entity> vars = new HashMap<>(lambdaVariables);
            vars.put(variable, element);
            boolean match = bool(new EntityExpressionVisitor(entity, navigation, vars).evaluate(body));
            if (any && match) return true;
            if (!any && !match) return false;
        }
        return !any;
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
        return member(member);
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
        throw unsupported("Lambda variables outside a navigation path");
    }

    @Override
    public Object visitLambdaExpression(String lambdaFunction, String lambdaVariable, Expression expression)
            throws ExpressionVisitException, ODataApplicationException {
        // Only reached through Member.accept, i.e. not via evaluate(); the collection path is unknown here.
        throw unsupported("Lambda expressions outside a navigation path");
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
