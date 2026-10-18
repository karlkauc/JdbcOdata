package at.jdbcodata.odata;

import org.apache.olingo.server.api.uri.queryoption.ExpandItem;
import org.apache.olingo.server.api.uri.queryoption.ExpandOption;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriResource;
import org.apache.olingo.server.api.uri.UriResourceCount;
import org.apache.olingo.server.api.uri.UriResourceNavigation;
import org.apache.olingo.server.api.uri.UriResourcePrimitiveProperty;
import org.apache.olingo.server.api.uri.queryoption.ApplyItem;
import org.apache.olingo.server.api.uri.queryoption.ApplyOption;
import org.apache.olingo.server.api.uri.queryoption.apply.Aggregate;
import org.apache.olingo.server.api.uri.queryoption.apply.AggregateExpression;
import org.apache.olingo.server.api.uri.queryoption.apply.BottomTop;
import org.apache.olingo.server.api.uri.queryoption.apply.Compute;
import org.apache.olingo.server.api.uri.queryoption.apply.ComputeExpression;
import org.apache.olingo.server.api.uri.queryoption.apply.Concat;
import org.apache.olingo.server.api.uri.queryoption.apply.Filter;
import org.apache.olingo.server.api.uri.queryoption.apply.GroupBy;
import org.apache.olingo.server.api.uri.queryoption.apply.GroupByItem;
import org.apache.olingo.server.api.uri.queryoption.apply.OrderBy;
import org.apache.olingo.server.api.uri.queryoption.apply.Search;
import org.apache.olingo.server.api.uri.queryoption.apply.Skip;
import org.apache.olingo.server.api.uri.queryoption.apply.Top;
import org.apache.olingo.server.api.uri.queryoption.expression.Expression;
import org.apache.olingo.server.api.uri.queryoption.expression.ExpressionVisitException;

/**
 * In-memory evaluation of {@code $apply} (OData Data Aggregation Extension) on the rows of one query.
 *
 * <p>Supported transformations: {@code filter}, {@code search}, {@code orderby}, {@code top}, {@code skip},
 * {@code identity}, {@code compute}, {@code concat}, {@code aggregate} ({@code sum}, {@code min}, {@code max},
 * {@code average}, {@code countdistinct}, {@code $count}), {@code groupby} (properties, also along to-one navigations,
 * with a nested transformation sequence), {@code topcount}/{@code bottomcount}, {@code topsum}/{@code bottomsum},
 * {@code toppercent}/{@code bottompercent}. Not supported: custom aggregates and functions, {@code expand},
 * {@code from}, inline aggregates over collections.
 *
 * <p>Aggregated results are dynamic entities: their properties are the group keys and the aliases. Group keys along a
 * navigation ({@code groupby((Kunde/Region))}) are emitted under the last segment's name ({@code Region}).
 */
final class ApplyEvaluator {

    private static final Locale LOCALE = Locale.ENGLISH;

    private final EvaluationContext navigation;

    ApplyEvaluator(EvaluationContext navigation) {
        this.navigation = navigation;
    }

    List<Entity> apply(List<Entity> input, ApplyOption option) throws ODataApplicationException {
        List<Entity> current = input;
        for (ApplyItem item : option.getApplyItems()) {
            current = transform(current, item);
        }
        return current;
    }

    private List<Entity> transform(List<Entity> in, ApplyItem item) throws ODataApplicationException {
        switch (item.getKind()) {
            case IDENTITY:
                return in;
            case FILTER: {
                Filter f = (Filter) item;
                return QueryOptionsEvaluator.apply(in, f.getFilterOption(), null, null, null, navigation).getEntities();
            }
            case SEARCH:
                return SearchEvaluator.filter(in, ((Search) item).getSearchOption());
            case ORDERBY:
                return QueryOptionsEvaluator.apply(in, null, ((OrderBy) item).getOrderByOption(), null, null, navigation)
                        .getEntities();
            case TOP:
                return QueryOptionsEvaluator.apply(in, null, null, null, ((Top) item).getTopOption(), navigation).getEntities();
            case SKIP:
                return QueryOptionsEvaluator.apply(in, null, null, ((Skip) item).getSkipOption(), null, navigation).getEntities();
            case COMPUTE:
                return compute(in, (Compute) item);
            case CONCAT: {
                List<Entity> out = new ArrayList<>();
                for (ApplyOption branch : ((Concat) item).getApplyOptions()) {
                    out.addAll(apply(in, branch));
                }
                return out;
            }
            case AGGREGATE:
                return List.of(aggregate(in, (Aggregate) item));
            case GROUP_BY:
                return groupBy(in, (GroupBy) item);
            case BOTTOM_TOP:
                return bottomTop(in, (BottomTop) item);
            case EXPAND:
                return expand(in, ((org.apache.olingo.server.api.uri.queryoption.apply.Expand) item).getExpandOption());
            default:
                throw unsupported("$apply transformation " + item.getKind().name().toLowerCase(LOCALE));
        }
    }

    // --- compute ------------------------------------------------------------------------------------------------

    private List<Entity> compute(List<Entity> in, Compute compute) throws ODataApplicationException {
        List<Entity> out = new ArrayList<>(in.size());
        for (Entity e : in) {
            Entity copy = copy(e);
            for (ComputeExpression c : compute.getExpressions()) {
                set(copy, c.getAlias(), evaluate(c.getExpression(), e));
            }
            out.add(copy);
        }
        return out;
    }

    // --- aggregate ----------------------------------------------------------------------------------------------

    private Entity aggregate(List<Entity> in, Aggregate aggregate) throws ODataApplicationException {
        Entity result = new Entity();
        for (AggregateExpression a : aggregate.getExpressions()) {
            AggregateExpression inline = a.getInlineAggregateExpression();
            if (inline != null) {
                // Umsaetze(Betrag with sum as S): aggregate over the related rows of all input rows.
                String alias = inline.getAlias() != null ? inline.getAlias() : a.getAlias();
                if (alias == null) throw bad("Inline aggregates need an alias ('as Name')");
                set(result, alias, aggregateValue(inline, related(in, a.getPath())));
                continue;
            }
            String alias = a.getAlias();
            if (alias == null && isCount(a)) alias = "$count";
            if (alias == null) throw bad("Aggregate expressions need an alias ('as Name')");
            set(result, alias, aggregateValue(a, in));
        }
        return result;
    }

    /** One aggregate over {@code rows}: {@code $count}, a standard method, optionally with {@code from}. */
    private Object aggregateValue(AggregateExpression a, List<Entity> rows) throws ODataApplicationException {
        if (isCount(a)) return BigDecimal.valueOf(rows.size());
        List<UriResource> path = a.getPath();
        boolean navigationCount = a.getExpression() == null && path != null && !path.isEmpty()
                && path.get(path.size() - 1) instanceof UriResourceCount;
        AggregateExpression.StandardMethod method = a.getStandardMethod();
        if (a.getCustomMethod() != null) {
            if (!DynamicEdmProvider.NAMESPACE.equals(a.getCustomMethod().getNamespace())) {
                throw unsupported("Aggregation method " + a.getCustomMethod().getFullQualifiedNameAsString());
            }
            List<Object> values = new ArrayList<>(rows.size());
            for (Entity e : rows) values.add(value(a, e));
            return customReduce(a.getCustomMethod().getName(), values);
        }
        if (method == null) {
            if (!navigationCount) throw unsupported("Aggregation without 'with <method>'");
            method = AggregateExpression.StandardMethod.SUM; // Umsaetze/$count as N: related rows in total
        }
        if (a.getFrom() != null && !a.getFrom().isEmpty()) {
            return fromAggregate(a, method, rows);
        }
        List<Object> values = new ArrayList<>(rows.size());
        for (Entity e : rows) values.add(value(a, e));
        return reduce(method, values);
    }

    /**
     * {@code Betrag with sum from Kunde with max from Monat with average as X}: aggregate per combination of the
     * {@code from} properties with the base method, then remove the {@code from} dimensions left to right, each
     * with its own method.
     */
    private Object fromAggregate(AggregateExpression a, AggregateExpression.StandardMethod method, List<Entity> rows)
            throws ODataApplicationException {
        List<AggregateExpression> froms = a.getFrom();
        for (AggregateExpression f : froms) {
            boolean hasTarget = (f.getPath() != null && !f.getPath().isEmpty()) || f.getExpression() != null;
            if (f.getStandardMethod() == null || !hasTarget) throw bad("'from' needs '<property> with <method>'");
        }
        Map<List<Object>, List<Object>> base = new LinkedHashMap<>();
        for (Entity e : rows) {
            List<Object> key = new ArrayList<>();
            for (AggregateExpression f : froms) key.add(normalized(value(f, e)));
            base.computeIfAbsent(key, k -> new ArrayList<>()).add(value(a, e));
        }
        Map<List<Object>, Object> level = new LinkedHashMap<>();
        for (Map.Entry<List<Object>, List<Object>> g : base.entrySet()) level.put(g.getKey(), reduce(method, g.getValue()));
        for (AggregateExpression f : froms) {
            Map<List<Object>, List<Object>> grouped = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, Object> g : level.entrySet()) {
                grouped.computeIfAbsent(g.getKey().subList(1, g.getKey().size()), k -> new ArrayList<>()).add(g.getValue());
            }
            Map<List<Object>, Object> next = new LinkedHashMap<>();
            for (Map.Entry<List<Object>, List<Object>> g : grouped.entrySet()) next.put(g.getKey(), reduce(f.getStandardMethod(), g.getValue()));
            level = next;
        }
        return level.isEmpty() ? null : level.values().iterator().next();
    }

    /** All rows reached from {@code in} over a collection navigation path (for inline aggregates). */
    private List<Entity> related(List<Entity> in, List<UriResource> path) throws ODataApplicationException {
        if (path == null || path.size() != 1 || !(path.get(0) instanceof UriResourceNavigation nav)) {
            throw unsupported("Inline aggregates other than over one navigation property");
        }
        if (navigation == null) throw unsupported("Navigation in $apply");
        List<Entity> out = new ArrayList<>();
        for (Entity e : in) out.addAll(navigation.navigate(e, nav.getProperty().getName()));
        return out;
    }

    private static boolean isCount(AggregateExpression a) {
        List<UriResource> path = a.getPath();
        return a.getExpression() == null && a.getStandardMethod() == null && path != null && path.size() == 1
                && path.get(0) instanceof UriResourceCount;
    }

    private Object value(AggregateExpression a, Entity e) throws ODataApplicationException {
        if (a.getExpression() != null) return evaluate(a.getExpression(), e);
        return path(a.getPath(), e);
    }

    private static Object normalized(Object v) {
        return v == null ? null : RelationJoin.normalize(v);
    }

    static Object reduce(AggregateExpression.StandardMethod method, List<Object> values) throws ODataApplicationException {
        List<Object> present = values.stream().filter(v -> v != null).toList();
        switch (method) {
            case COUNT_DISTINCT: {
                Set<Object> distinct = new HashSet<>();
                for (Object v : present) distinct.add(RelationJoin.normalize(v));
                return BigDecimal.valueOf(distinct.size());
            }
            case MIN:
            case MAX: {
                Object best = null;
                for (Object v : present) {
                    if (best == null) {
                        best = v;
                        continue;
                    }
                    Integer c = FilterValues.compare(v, best);
                    if (c == null) throw bad("Values cannot be compared for " + method.name().toLowerCase(LOCALE));
                    if (method == AggregateExpression.StandardMethod.MIN ? c < 0 : c > 0) best = v;
                }
                return best;
            }
            case SUM:
            case AVERAGE: {
                BigDecimal sum = BigDecimal.ZERO;
                for (Object v : present) {
                    if (!(v instanceof BigDecimal bd)) throw bad(method.name().toLowerCase(LOCALE) + " needs numeric values");
                    sum = sum.add(bd);
                }
                if (method == AggregateExpression.StandardMethod.SUM) return present.isEmpty() ? null : sum;
                if (present.isEmpty()) return null;
                return sum.divide(BigDecimal.valueOf(present.size()), 10, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default:
                throw unsupported("Aggregate method " + method);
        }
    }

    // --- groupby ------------------------------------------------------------------------------------------------

    /**
     * {@code groupby((P1,rollup(P2,P3)),…)}: one grouping per rollup level — (P1,P2,P3), (P1,P2), and with
     * {@code rollup($all,…)} also (P1) — each with the nested transformations; subtotal rows simply lack the
     * rolled-up properties.
     */
    private List<Entity> groupBy(List<Entity> in, GroupBy groupBy) throws ODataApplicationException {
        List<Entity> out = new ArrayList<>();
        for (List<List<UriResource>> grouping : groupings(groupBy)) out.addAll(group(in, grouping, groupBy.getApplyOption()));
        return out;
    }

    /** The groupings of a groupby: one, or one per rollup level combination. */
    private static List<List<List<UriResource>>> groupings(GroupBy groupBy) {
        List<List<UriResource>> fixed = new ArrayList<>();
        List<List<List<UriResource>>> rollupLevels = new ArrayList<>();
        for (GroupByItem g : groupBy.getGroupByItems()) {
            if (g.getRollup() != null && !g.getRollup().isEmpty()) {
                List<List<UriResource>> paths = new ArrayList<>();
                for (GroupByItem r : g.getRollup()) paths.add(r.getPath());
                rollupLevels.add(paths);
                if (g.isRollupAll()) rollupLevels.set(rollupLevels.size() - 1, withAll(paths));
            } else if (g.getPath() != null && !g.getPath().isEmpty()) {
                fixed.add(g.getPath());
            }
        }
        List<List<List<UriResource>>> groupings = new ArrayList<>();
        groupings.add(new ArrayList<>(fixed));
        for (List<List<UriResource>> rollup : rollupLevels) {
            boolean all = rollup.get(0) == null;
            List<List<UriResource>> paths = all ? rollup.subList(1, rollup.size()) : rollup;
            int min = all ? 0 : 1;
            List<List<List<UriResource>>> next = new ArrayList<>();
            for (List<List<UriResource>> grouping : groupings) {
                for (int n = paths.size(); n >= min; n--) {
                    List<List<UriResource>> g = new ArrayList<>(grouping);
                    g.addAll(paths.subList(0, n));
                    next.add(g);
                }
            }
            groupings = next;
        }
        return groupings;
    }

    /** Marks a rollup that includes the grand total ({@code rollup($all,…)}) with a leading null. */
    private static List<List<UriResource>> withAll(List<List<UriResource>> paths) {
        List<List<UriResource>> marked = new ArrayList<>();
        marked.add(null);
        marked.addAll(paths);
        return marked;
    }

    private List<Entity> group(List<Entity> in, List<List<UriResource>> paths, ApplyOption nested)
            throws ODataApplicationException {
        Map<List<Object>, List<Entity>> groups = new LinkedHashMap<>();
        Map<List<Object>, List<Object>> keyValues = new LinkedHashMap<>();
        for (Entity e : in) {
            List<Object> values = new ArrayList<>(paths.size());
            List<Object> key = new ArrayList<>(paths.size());
            for (List<UriResource> path : paths) {
                Object v = path(path, e);
                values.add(v);
                key.add(normalized(v));
            }
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            keyValues.putIfAbsent(key, values);
        }
        if (groups.isEmpty() && paths.isEmpty()) groups.put(List.of(), List.of()); // grand total of nothing
        List<Entity> out = new ArrayList<>();
        for (Map.Entry<List<Object>, List<Entity>> group : groups.entrySet()) {
            List<Entity> rows = nested == null ? List.of(new Entity()) : apply(group.getValue(), nested);
            List<Object> values = keyValues.getOrDefault(group.getKey(), List.of());
            for (Entity row : rows) {
                Entity result = new Entity();
                for (int i = 0; i < values.size(); i++) set(result, name(paths.get(i)), values.get(i));
                for (Property p : row.getProperties()) {
                    if (result.getProperty(p.getName()) == null) result.addProperty(p);
                }
                out.add(result);
            }
        }
        return out;
    }

    // --- topcount & co. -----------------------------------------------------------------------------------------

    private List<Entity> bottomTop(List<Entity> in, BottomTop bt) throws ODataApplicationException {
        Object n = evaluate(bt.getNumber(), in.isEmpty() ? new Entity() : in.get(0));
        if (!(n instanceof BigDecimal limit)) throw bad("The first argument of " + bt.getMethod() + " must be a number");
        Map<Entity, Object> values = new IdentityHashMap<>();
        for (Entity e : in) values.put(e, evaluate(bt.getValue(), e));
        boolean top = bt.getMethod().name().startsWith("TOP");
        Comparator<Entity> byValue = (a, b) -> {
            Integer c = FilterValues.compare(values.get(a), values.get(b));
            if (values.get(a) == null) return values.get(b) == null ? 0 : 1;
            if (values.get(b) == null) return -1;
            return c == null ? 0 : (top ? -c : c);
        };
        List<Entity> sorted = new ArrayList<>(in);
        sorted.sort(byValue);
        switch (bt.getMethod()) {
            case TOP_COUNT:
            case BOTTOM_COUNT:
                return sorted.subList(0, Math.min(sorted.size(), Math.max(0, limit.intValue())));
            case TOP_SUM:
            case BOTTOM_SUM:
                return takeUntil(sorted, values, limit);
            case TOP_PERCENT:
            case BOTTOM_PERCENT: {
                BigDecimal total = BigDecimal.ZERO;
                for (Entity e : sorted) total = total.add(number(values.get(e)));
                return takeUntil(sorted, values, total.multiply(limit).divide(BigDecimal.valueOf(100), 10, RoundingMode.HALF_UP));
            }
            default:
                throw unsupported(bt.getMethod().name());
        }
    }

    /** The shortest prefix whose sum reaches {@code threshold} (all rows if the total stays below). */
    private static List<Entity> takeUntil(List<Entity> sorted, Map<Entity, Object> values, BigDecimal threshold)
            throws ODataApplicationException {
        List<Entity> out = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        for (Entity e : sorted) {
            if (sum.compareTo(threshold) >= 0) break;
            out.add(e);
            sum = sum.add(number(values.get(e)));
        }
        return out;
    }

    // --- helpers ------------------------------------------------------------------------------------------------

    private Object evaluate(Expression expr, Entity e) throws ODataApplicationException {
        try {
            return new EntityExpressionVisitor(e, navigation).evaluate(expr);
        } catch (ExpressionVisitException ex) {
            throw bad("Invalid expression: " + ex.getMessage());
        }
    }

    private Object path(List<UriResource> path, Entity e) throws ODataApplicationException {
        if (path == null || path.isEmpty()) throw bad("Empty path in $apply");
        try {
            return new EntityExpressionVisitor(e, navigation).path(path);
        } catch (ExpressionVisitException ex) {
            throw bad("Invalid path: " + ex.getMessage());
        }
    }

    private static String name(List<UriResource> path) throws ODataApplicationException {
        UriResource last = path.get(path.size() - 1);
        if (!(last instanceof UriResourcePrimitiveProperty p)) throw unsupported("groupby on non-primitive paths");
        return p.getProperty().getName();
    }

    private static BigDecimal number(Object v) throws ODataApplicationException {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal bd) return bd;
        throw bad("Expected a number");
    }

    static Entity copy(Entity e) {
        Entity c = new Entity();
        c.setType(e.getType());
        c.setId(e.getId());
        for (Property p : e.getProperties()) c.addProperty(p);
        c.getNavigationLinks().addAll(e.getNavigationLinks());
        return c;
    }

    // --- expand transformation --------------------------------------------------------------------------------

    /**
     * {@code expand(Umsaetze,filter(Betrag gt 1000),expand(Produkt))}: every row keeps its place and gets the
     * related rows (restricted by the nested filter, expanded further by the nested expand) attached.
     */
    private List<Entity> expand(List<Entity> in, ExpandOption option) throws ODataApplicationException {
        if (option == null) return in;
        if (navigation == null) throw unsupported("expand in $apply");
        List<Entity> out = new ArrayList<>(in.size());
        for (Entity e : in) {
            Entity copy = copy(e);
            for (ExpandItem item : option.getExpandItems()) {
                List<UriResource> parts = item.getResourcePath() == null ? List.of() : item.getResourcePath().getUriResourceParts();
                if (parts.size() != 1 || !(parts.get(0) instanceof UriResourceNavigation nav)) {
                    throw unsupported("expand transformation other than over one navigation property");
                }
                String name = nav.getProperty().getName();
                List<Entity> targets = navigation.navigate(e, name);
                if (item.getFilterOption() != null) {
                    targets = QueryOptionsEvaluator.apply(targets, item.getFilterOption(), null, null, null, navigation).getEntities();
                }
                if (item.getExpandOption() != null) targets = expand(targets, item.getExpandOption());
                copy.getNavigationLinks().removeIf(l -> name.equals(l.getTitle()));
                copy.getNavigationLinks().add(nav.getProperty().isCollection() ? ApplyLink.collection(name, targets)
                        : ApplyLink.single(name, targets.isEmpty() ? null : targets.get(0)));
            }
            out.add(copy);
        }
        return out;
    }

    // --- custom aggregation methods ---------------------------------------------------------------------------

    /** Custom aggregation methods ({@code Betrag with JdbcOdata.median as M}), declared in $metadata. */
    static final List<String> CUSTOM_METHODS = List.of("median", "stddev", "stddevp", "variance", "variancep");

    static Object customReduce(String method, List<Object> values) throws ODataApplicationException {
        List<BigDecimal> numbers = new ArrayList<>();
        for (Object v : values) {
            if (v == null) continue;
            if (!(v instanceof BigDecimal bd)) throw bad(method + " needs numeric values");
            numbers.add(bd);
        }
        if (numbers.isEmpty()) return null;
        java.math.MathContext mc = new java.math.MathContext(20, RoundingMode.HALF_UP);
        switch (method) {
            case "median": {
                List<BigDecimal> sorted = new ArrayList<>(numbers);
                sorted.sort(null);
                int n = sorted.size();
                BigDecimal m = n % 2 == 1 ? sorted.get(n / 2)
                        : sorted.get(n / 2 - 1).add(sorted.get(n / 2)).divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
                return m.stripTrailingZeros();
            }
            case "stddev", "stddevp", "variance", "variancep": {
                boolean population = method.endsWith("p");
                int n = numbers.size();
                if (!population && n < 2) return null;
                BigDecimal mean = numbers.stream().reduce(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal.valueOf(n), mc);
                BigDecimal squares = BigDecimal.ZERO;
                for (BigDecimal x : numbers) squares = squares.add(x.subtract(mean).pow(2, mc), mc);
                BigDecimal variance = squares.divide(BigDecimal.valueOf(population ? n : n - 1), mc);
                BigDecimal result = method.startsWith("stddev") ? variance.sqrt(mc) : variance;
                return result.setScale(10, RoundingMode.HALF_UP).stripTrailingZeros();
            }
            default:
                throw bad("Aggregation method " + DynamicEdmProvider.NAMESPACE + "." + method
                        + " (available: " + String.join(", ", CUSTOM_METHODS) + ")");
        }
    }

    // --- grouping for extended pipelines ----------------------------------------------------------------------

    /**
     * {@code groupby((P1,P2))} without nested transformations, as groups: a row with the group properties and the
     * member rows. Used when the nested transformations are evaluated elsewhere (join, nest, …). With rollup, the
     * groups of all levels follow each other (subtotal groups lack the rolled-up properties).
     */
    Map<Entity, List<Entity>> groups(List<Entity> in, GroupBy groupBy) throws ODataApplicationException {
        Map<Entity, List<Entity>> out = new LinkedHashMap<>();
        for (List<List<UriResource>> paths : groupings(groupBy)) out.putAll(groups(in, paths));
        return out;
    }

    private Map<Entity, List<Entity>> groups(List<Entity> in, List<List<UriResource>> paths) throws ODataApplicationException {
        Map<List<Object>, List<Entity>> members = new LinkedHashMap<>();
        Map<List<Object>, List<Object>> keyValues = new LinkedHashMap<>();
        for (Entity e : in) {
            List<Object> values = new ArrayList<>();
            List<Object> key = new ArrayList<>();
            for (List<UriResource> p : paths) {
                Object v = path(p, e);
                values.add(v);
                key.add(normalized(v));
            }
            members.computeIfAbsent(key, k -> new ArrayList<>()).add(e);
            keyValues.putIfAbsent(key, values);
        }
        if (members.isEmpty() && paths.isEmpty()) {
            members.put(List.of(), List.of()); // grand total of nothing
            keyValues.put(List.of(), List.of());
        }
        Map<Entity, List<Entity>> out = new LinkedHashMap<>();
        for (Map.Entry<List<Object>, List<Entity>> g : members.entrySet()) {
            Entity row = new Entity();
            List<Object> values = keyValues.get(g.getKey());
            for (int i = 0; i < paths.size(); i++) set(row, name(paths.get(i)), values.get(i));
            out.put(row, g.getValue());
        }
        return out;
    }

    private static void set(Entity e, String name, Object value) {
        Property existing = e.getProperty(name);
        if (existing != null) e.getProperties().remove(existing);
        e.addProperty(new Property(null, name, ValueType.PRIMITIVE, value));
    }

    private static ODataApplicationException bad(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
    }

    private static ODataApplicationException unsupported(String what) {
        return new ODataApplicationException(what + " not supported", HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
    }
}
