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
import org.apache.olingo.server.api.uri.UriResourceEntitySet;
import org.apache.olingo.server.api.uri.UriResourceIt;
import org.apache.olingo.server.api.uri.UriResourceRoot;
import org.apache.olingo.server.api.uri.UriResourceLambdaAll;
import org.apache.olingo.server.api.uri.UriResourceLambdaAny;
import org.apache.olingo.server.api.uri.UriResourceLambdaVariable;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.expression.Binary;
import org.apache.olingo.server.api.uri.queryoption.expression.BinaryOperatorKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitor;
import org.apache.olingo.server.api.uri.queryoption.expression.Literal;
import org.apache.olingo.server.api.uri.queryoption.expression.Member;
import org.apache.olingo.server.api.uri.queryoption.expression.Method;
import org.apache.olingo.server.api.uri.queryoption.expression.TypeLiteral;
import org.apache.olingo.server.api.uri.queryoption.expression.MethodKind;
import org.apache.olingo.server.api.uri.queryoption.expression.Unary;
import org.apache.olingo.server.api.uri.queryoption.expression.UnaryOperatorKind;

/**
 * Evaluates an OData expression against one entity. Values are canonicalised via {@link FilterValues}.
 *
 * <p>Member paths may follow navigation properties when a {@link EvaluationContext} is given: single-valued
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
    private final EvaluationContext navigation;
    private final Map<String, Entity> lambdaVariables;

    EntityExpressionVisitor(Entity entity) {
        this(entity, null, Map.of());
    }

    EntityExpressionVisitor(Entity entity, EvaluationContext navigation) {
        this(entity, navigation, Map.of());
    }

    private EntityExpressionVisitor(Entity entity, EvaluationContext navigation, Map<String, Entity> lambdaVariables) {
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
        if (expr instanceof Method m && (m.getMethod() == MethodKind.CAST || m.getMethod() == MethodKind.ISOF)) {
            return castOrIsOf(m);
        }
        if (expr instanceof Method m) {
            List<Object> params = new ArrayList<>();
            for (Expression e : m.getParameters()) params.add(evaluate(e));
            return visitMethodCall(m.getMethod(), params);
        }
        return expr.accept(this);
    }

    private Object member(Member member) throws ExpressionVisitException, ODataApplicationException {
        return path(member.getResourcePath().getUriResourceParts());
    }

    /** Value of a member path (also used for $apply aggregate and groupby paths). */
    Object path(List<UriResource> parts) throws ExpressionVisitException, ODataApplicationException {
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
            if (part instanceof UriResourceIt && i == 0) {
                // $it: the entity the expression is evaluated for (also inside lambdas).
                if (last) throw unsupported("Comparing whole entities");
                current = entity;
                continue;
            }
            if (part instanceof UriResourceEntitySet es && i == 0 && entity.getType() == null
                    && entity.getNavigationLink(es.getEntitySet().getName()) != null) {
                // $crossjoin row: the first segment names the entity set of the combination.
                current = entity.getNavigationLink(es.getEntitySet().getName()).getInlineEntity();
                if (last) throw unsupported("Comparing whole entities");
                continue;
            }
            if (part instanceof UriResourceRoot && i == 0) {
                if (navigation == null || i + 1 >= parts.size() || !(parts.get(i + 1) instanceof UriResourceEntitySet es)
                        || es.getKeyPredicates().isEmpty()) {
                    throw unsupported("$root without EntitySet(key)");
                }
                current = navigation.lookup(es.getEntitySet().getName(), es.getKeyPredicates());
                i++;
                if (i == parts.size() - 1) throw unsupported("Comparing whole entities");
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
                if (next instanceof UriResourceLambdaAny any && any.getLambdaVariable().startsWith(ExpressionRewriter.COUNT_VARIABLE)) {
                    return count(targets, any.getLambdaVariable(), any.getExpression()); // Nav/$count($filter=…)
                }
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

    /**
     * {@code isof(Type)} tests the entity type, {@code isof(expr, Type)} and {@code cast(expr, Type)} convert a
     * primitive value; a failed cast yields null, as the specification requires.
     */
    private Object castOrIsOf(Method m) throws ExpressionVisitException, ODataApplicationException {
        List<Expression> params = m.getParameters();
        if (params.isEmpty() || !(params.get(params.size() - 1) instanceof TypeLiteral typeLiteral)) {
            throw bad(m.getMethod().name().toLowerCase(LOCALE) + " needs a type argument");
        }
        EdmType type = typeLiteral.getType();
        String fqn = type.getFullQualifiedName().getFullQualifiedNameAsString();
        if (params.size() == 1) {
            if (m.getMethod() == MethodKind.ISOF) return fqn.equals(entity.getType());
            throw unsupported("cast of whole entities");
        }
        Object value = evaluate(params.get(0));
        Object converted = convert(value, type);
        if (m.getMethod() == MethodKind.CAST) return converted;
        return value != null && converted != null;
    }

    /** Converts a canonical value to the given primitive type; null if not possible. */
    static Object convert(Object value, EdmType type) throws ODataApplicationException {
        if (value == null) return null;
        EdmPrimitiveTypeKind kind;
        try {
            kind = EdmPrimitiveTypeKind.valueOfFQN(type.getFullQualifiedName());
        } catch (IllegalArgumentException e) {
            return null; // not a primitive type
        }
        String text = value instanceof BigDecimal bd ? bd.stripTrailingZeros().toPlainString() : value.toString();
        try {
            return switch (kind) {
                case String -> text;
                case Boolean -> value instanceof Boolean ? value
                        : "true".equalsIgnoreCase(text) ? Boolean.TRUE : "false".equalsIgnoreCase(text) ? Boolean.FALSE : null;
                case Byte, SByte, Int16, Int32, Int64 -> {
                    BigDecimal n = value instanceof BigDecimal bd ? bd : new BigDecimal(text.strip());
                    yield n.setScale(0, RoundingMode.DOWN);
                }
                case Decimal, Double, Single -> value instanceof BigDecimal ? value : new BigDecimal(text.strip());
                case Date -> value instanceof LocalDate ? value
                        : value instanceof Instant i ? LocalDate.ofInstant(i, java.time.ZoneOffset.UTC) : LocalDate.parse(text.strip());
                case DateTimeOffset -> value instanceof Instant ? value
                        : value instanceof LocalDate d ? d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant()
                        : OffsetDateTime.parse(text.strip()).toInstant();
                case TimeOfDay -> value instanceof LocalTime ? value : LocalTime.parse(text.strip());
                default -> null;
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            return null;
        }
    }

    /** {@code Nav/$count($filter=…)} (rewritten into a tagged lambda): number of elements matching the body. */
    private BigDecimal count(List<Entity> elements, String variable, Expression body)
            throws ExpressionVisitException, ODataApplicationException {
        long n = 0;
        for (Entity element : elements) {
            Map<String, Entity> vars = new HashMap<>(lambdaVariables);
            vars.put(variable, element);
            if (bool(new EntityExpressionVisitor(entity, navigation, vars).evaluate(body))) n++;
        }
        return BigDecimal.valueOf(n);
    }

    private static final Map<String, java.util.regex.Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();

    /** {@code matchesPattern}: the regular expression matches somewhere in the value (use ^ and $ to anchor). */
    static boolean matchesPattern(String value, String regex) throws ODataApplicationException {
        java.util.regex.Pattern p;
        try {
            p = PATTERNS.size() > 1000 ? java.util.regex.Pattern.compile(regex)
                    : PATTERNS.computeIfAbsent(regex, java.util.regex.Pattern::compile);
        } catch (java.util.regex.PatternSyntaxException e) {
            throw new ODataApplicationException("Invalid pattern in matchesPattern: " + e.getDescription(),
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
        try {
            long[] request = REQUEST_REGEX_BUDGET.get();
            long[] row = {Math.min(REGEX_STEPS, request[0])};
            try {
                return p.matcher(new BudgetedChars(value, row)).find();
            } finally {
                request[0] -= Math.min(REGEX_STEPS, request[0]) - Math.max(row[0], 0);
            }
        } catch (RegexBudgetExceeded e) {
            throw new ODataApplicationException("matchesPattern: the pattern is too expensive to evaluate",
                    HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
        }
    }

    /** Character reads one regex match may perform; stops catastrophic backtracking ("(.*a){12}$"). */
    private static final long REGEX_STEPS = 100_000;
    /** Character reads all matchesPattern calls of one request may perform together. */
    private static final long REQUEST_REGEX_STEPS = 20_000_000;
    private static final ThreadLocal<long[]> REQUEST_REGEX_BUDGET = ThreadLocal.withInitial(() -> new long[] {REQUEST_REGEX_STEPS});

    /** Called at the start of every OData request (same thread evaluates the whole request). */
    static void resetRegexBudget() {
        REQUEST_REGEX_BUDGET.get()[0] = REQUEST_REGEX_STEPS;
    }

    private static final class RegexBudgetExceeded extends RuntimeException {
        RegexBudgetExceeded() {
            super(null, null, false, false);
        }
    }

    /** A CharSequence that counts reads against a shared budget. */
    private record BudgetedChars(CharSequence text, long[] budget) implements CharSequence {
        @Override
        public int length() {
            return text.length();
        }

        @Override
        public char charAt(int index) {
            if (--budget[0] < 0) throw new RegexBudgetExceeded();
            return text.charAt(index);
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new BudgetedChars(text.subSequence(start, end), budget);
        }

        @Override
        public String toString() {
            return text.toString();
        }
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
            // Ordering comparisons with null are false (as in SQL), in every direction.
            case GT -> left != null && right != null && ordered(left, right) > 0;
            case GE -> left != null && right != null && ordered(left, right) >= 0;
            case LT -> left != null && right != null && ordered(left, right) < 0;
            case LE -> left != null && right != null && ordered(left, right) <= 0;
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
                if (method == MethodKind.CONTAINS && b.startsWith(ExpressionRewriter.PATTERN_MARKER)) {
                    return matchesPattern(a, b.substring(ExpressionRewriter.PATTERN_MARKER.length()));
                }
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

    /** {@code @name}: evaluates the alias value given in the query string (an expression or a literal). */
    @Override
    public Object visitAlias(String aliasName) throws ExpressionVisitException, ODataApplicationException {
        String bare = aliasName.startsWith("@") ? aliasName : "@" + aliasName;
        if (bare.startsWith(ExpressionRewriter.CASE_ALIAS) && navigation != null) {
            List<Expression[]> branches = navigation.caseBranches(bare, entity);
            if (branches != null) {
                // case(c1:v1,…): the value of the first true condition, null when none is true
                for (Expression[] b : branches) {
                    if (Boolean.TRUE.equals(evaluate(b[0]))) return evaluate(b[1]);
                }
                return null;
            }
        }
        AliasQueryOption alias = navigation == null ? null : navigation.alias(aliasName);
        if (alias == null) throw bad("Parameter alias '" + aliasName + "' is not defined in the query string");
        if (alias.getValue() != null) return evaluate(alias.getValue());
        return aliasLiteral(alias.getText());
    }

    /** Literal text of an alias that Olingo did not parse into an expression. */
    static Object aliasLiteral(String text) throws ODataApplicationException {
        if (text == null || text.isBlank() || "null".equals(text.strip())) return null;
        String t = text.strip();
        if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) return unquote(t);
        if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) return Boolean.parseBoolean(t);
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            // not a number
        }
        try {
            return LocalDate.parse(t);
        } catch (DateTimeParseException e) {
            // not a date
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException e) {
            throw bad("Cannot interpret the value of the parameter alias: " + t);
        }
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
