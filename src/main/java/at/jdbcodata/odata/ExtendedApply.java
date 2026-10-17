package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmStructuredType;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.queryoption.ApplyItem;
import org.apache.olingo.server.api.uri.queryoption.ApplyOption;
import org.apache.olingo.server.api.uri.queryoption.apply.GroupBy;

/**
 * $apply transformations the Olingo 5 parser does not know, evaluated around the ones it does:
 *
 * <ul>
 *   <li>{@code join(Nav as A[,T])} / {@code outerjoin(Nav as A[,T])}: one row per related row, which is available
 *       as single-valued navigation {@code A}; outerjoin keeps rows without related rows ({@code A} null).</li>
 *   <li>{@code nest(T1 as A1,…)}: one row with collection-valued navigations {@code Ai} holding the result of
 *       {@code Ti} (inside {@code groupby}: per group).</li>
 *   <li>{@code ancestors(H,Q,p,T[,d][,keep start])}, {@code descendants(…)} and
 *       {@code traverse(H,Q,p,preorder|postorder[,T])} over a recursive hierarchy: {@code H} is {@code $root/Set},
 *       {@code Q} the to-one relation of the query to itself (its name is the hierarchy qualifier), {@code p} the node
 *       property.</li>
 * </ul>
 *
 * The pipeline is split at top-level {@code /}; runs of standard transformations are parsed by Olingo against the
 * type of the current rows ({@link RowType}), so they can use the aliases introduced before.
 */
final class ExtendedApply {

    /** What the evaluation needs from the processor. */
    interface Host {
        EvaluationContext context();

        /** Parses a standard transformation sequence for rows of {@code type}. */
        ApplyOption parseApply(String text, EdmStructuredType type) throws ODataApplicationException;

        EdmEntityType entityType(String entitySet);

        org.apache.olingo.commons.api.edm.Edm edm();
    }

    private static final Set<String> EXTENDED = Set.of("join", "outerjoin", "nest", "ancestors", "descendants", "traverse");
    private static final Pattern EXTENDED_INSIDE =
            Pattern.compile("(?<![A-Za-z0-9_/.$])(join|outerjoin|nest|ancestors|descendants|traverse)\\s*\\(");
    private static final Pattern AS = Pattern.compile("^(.*\\S)\\s+as\\s+([A-Za-z_][A-Za-z0-9_]*)$", Pattern.DOTALL);
    private static final Locale LOCALE = Locale.ENGLISH;

    private final Host host;
    private EdmStructuredType type;

    ExtendedApply(Host host, EdmStructuredType startType) {
        this.host = host;
        this.type = startType;
    }

    /** Type of the rows after the last {@link #run}: for parsing $filter, $orderby, $select and $expand. */
    EdmStructuredType type() {
        return type;
    }

    List<Entity> run(List<Entity> rows, String pipeline) throws ODataApplicationException {
        List<String> steps = split(pipeline.strip(), '/');
        List<String> standard = new ArrayList<>();
        for (String step : steps) {
            String name = name(step);
            boolean extended = EXTENDED.contains(name)
                    || (("groupby".equals(name) || "concat".equals(name)) && EXTENDED_INSIDE.matcher(withoutStrings(step)).find());
            if (!extended) {
                standard.add(step);
                continue;
            }
            rows = standard(rows, standard);
            standard.clear();
            rows = extended(rows, name, step);
        }
        return standard(rows, standard);
    }

    private List<Entity> standard(List<Entity> rows, List<String> steps) throws ODataApplicationException {
        if (steps.isEmpty()) return rows;
        ApplyOption option = host.parseApply(String.join("/", steps), type);
        List<Entity> out = new ApplyEvaluator(host.context()).apply(rows, option);
        type = RowType.of(out, type, host.edm());
        return out;
    }

    private List<Entity> extended(List<Entity> rows, String name, String step) throws ODataApplicationException {
        List<String> args = split(inner(step), ',');
        return switch (name) {
            case "join", "outerjoin" -> join(rows, args, "outerjoin".equals(name));
            case "nest" -> {
                Entity row = nest(rows, args);
                List<Entity> out = List.of(row);
                type = RowType.of(out, null, host.edm());
                yield out;
            }
            case "groupby" -> groupBy(rows, step, args);
            case "concat" -> {
                EdmStructuredType before = type;
                List<Entity> out = new ArrayList<>();
                for (String branch : args) {
                    type = before;
                    out.addAll(run(rows, branch));
                }
                type = RowType.of(out, before, host.edm());
                yield out;
            }
            case "ancestors", "descendants" -> hierarchy(rows, args, "ancestors".equals(name));
            case "traverse" -> traverse(rows, args);
            default -> throw bad("Unknown transformation " + name);
        };
    }

    // --- join ---------------------------------------------------------------------------------------------------

    private List<Entity> join(List<Entity> rows, List<String> args, boolean outer) throws ODataApplicationException {
        Matcher m = AS.matcher(args.get(0).strip());
        if (!m.matches()) throw bad((outer ? "outerjoin" : "join") + " needs 'NavigationProperty as Alias'");
        String nav = m.group(1).strip();
        String alias = m.group(2);
        EdmNavigationProperty property = type.getNavigationProperty(nav);
        if (property == null || nav.contains("/")) throw bad("'" + nav + "' is no navigation property here");
        if (type.getProperty(alias) != null && type.getNavigationProperty(alias) == null) {
            throw bad("Alias '" + alias + "' is already a property");
        }
        String nested = args.size() > 1 ? String.join(",", args.subList(1, args.size())) : null;
        List<Entity> out = new ArrayList<>();
        EdmStructuredType outerType = type;
        for (Entity e : rows) {
            List<Entity> targets = host.context().navigate(e, nav);
            if (nested != null && !nested.isBlank()) {
                type = property.getType();
                targets = run(targets, nested);
            }
            if (targets.isEmpty() && outer) {
                Entity row = ApplyEvaluator.copy(e);
                row.setId(null);
                row.getNavigationLinks().add(ApplyLink.single(alias, null));
                out.add(row);
            }
            for (Entity t : targets) {
                Entity row = ApplyEvaluator.copy(e);
                row.setId(null); // several rows per source entity: no longer addressable
                row.getNavigationLinks().add(ApplyLink.single(alias, t));
                out.add(row);
            }
        }
        type = RowType.withNavigation(RowType.of(out, outerType, host.edm()), alias, property.getType(), false);
        return out;
    }

    // --- nest ---------------------------------------------------------------------------------------------------

    private Entity nest(List<Entity> rows, List<String> args) throws ODataApplicationException {
        Entity row = new Entity();
        EdmStructuredType before = type;
        for (String arg : args) {
            Matcher m = AS.matcher(arg.strip());
            if (!m.matches()) throw bad("nest needs 'transformations as Alias'");
            type = before;
            List<Entity> nested = run(rows, m.group(1));
            row.getNavigationLinks().add(ApplyLink.collection(m.group(2), nested));
        }
        type = before;
        return row;
    }

    // --- groupby with extended nested transformations -------------------------------------------------------------

    private List<Entity> groupBy(List<Entity> rows, String step, List<String> args) throws ODataApplicationException {
        if (args.size() < 2) throw bad("groupby with nest/join needs the grouping properties and transformations");
        ApplyOption grouping = host.parseApply("groupby(" + args.get(0) + ")", type);
        ApplyItem item = grouping.getApplyItems().get(0);
        if (!(item instanceof GroupBy gb)) throw bad("groupby expected");
        String nested = String.join(",", args.subList(1, args.size()));
        EdmStructuredType before = type;
        List<Entity> out = new ArrayList<>();
        for (Map.Entry<Entity, List<Entity>> g : new ApplyEvaluator(host.context()).groups(rows, gb).entrySet()) {
            type = before;
            for (Entity r : run(g.getValue(), nested)) {
                Entity result = new Entity();
                for (Property p : g.getKey().getProperties()) result.addProperty(p);
                for (Property p : r.getProperties()) if (result.getProperty(p.getName()) == null) result.addProperty(p);
                result.getNavigationLinks().addAll(r.getNavigationLinks());
                out.add(result);
            }
        }
        type = RowType.of(out, before, host.edm());
        return out;
    }

    // --- recursive hierarchies ------------------------------------------------------------------------------------

    /** Parents and children of a recursive hierarchy, keyed by the normalised node property value. */
    private record Hierarchy(Map<Object, Object> parent, Map<Object, List<Object>> children, List<Object> order) {
    }

    private Hierarchy hierarchy(String nodes, String qualifier, String nodeProperty) throws ODataApplicationException {
        String set = nodes.strip();
        if (!set.startsWith("$root/")) throw bad("The hierarchy nodes must be given as $root/EntitySet");
        set = set.substring("$root/".length());
        EdmEntityType setType = host.entityType(set);
        if (setType == null) throw bad("Unknown entity set '" + set + "'");
        EdmNavigationProperty parentNav = setType.getNavigationProperty(qualifier.strip());
        if (parentNav == null || parentNav.isCollection() || !parentNav.getType().getFullQualifiedName().equals(setType.getFullQualifiedName())) {
            throw bad("Hierarchy '" + qualifier.strip() + "' is no to-one relation of " + set + " to itself");
        }
        String p = nodeProperty.strip();
        Map<Object, Object> parent = new LinkedHashMap<>();
        Map<Object, List<Object>> children = new LinkedHashMap<>();
        List<Object> order = new ArrayList<>();
        for (Entity h : host.context().entitySet(set)) {
            Object node = node(h, p);
            if (node == null) continue;
            order.add(node);
            List<Entity> up = host.context().navigate(h, parentNav.getName());
            Object parentNode = up.isEmpty() ? null : node(up.get(0), p);
            parent.put(node, parentNode);
            if (parentNode != null) children.computeIfAbsent(parentNode, k -> new ArrayList<>()).add(node);
        }
        return new Hierarchy(parent, children, order);
    }

    private static Object node(Entity e, String property) {
        Property p = e.getProperty(property);
        return p == null || p.getValue() == null ? null : RelationJoin.normalize(p.getValue());
    }

    private List<Entity> hierarchy(List<Entity> rows, List<String> args, boolean ancestors) throws ODataApplicationException {
        String fn = ancestors ? "ancestors" : "descendants";
        if (args.size() < 4) throw bad(fn + "(H, Q, p, T[, maxDistance][, keep start]) expected");
        Hierarchy h = hierarchy(args.get(0), args.get(1), args.get(2));
        String p = args.get(2).strip();
        Integer maxDistance = null;
        boolean keepStart = false;
        for (String extra : args.subList(4, args.size())) {
            String x = extra.strip();
            if (x.equalsIgnoreCase("keep start")) keepStart = true;
            else if (x.matches("\\d+")) maxDistance = Integer.parseInt(x);
            else throw bad(fn + ": unexpected argument '" + x + "'");
        }
        EdmStructuredType before = type;
        List<Entity> start = run(rows, args.get(3));
        type = before;
        Set<Object> wanted = new HashSet<>();
        for (Entity s : start) {
            Object node = node(s, p);
            if (node == null) continue;
            if (keepStart) wanted.add(node);
            if (ancestors) {
                Object current = h.parent().get(node);
                Set<Object> seen = new HashSet<>(Set.of(node));
                for (int d = 1; current != null && seen.add(current) && (maxDistance == null || d <= maxDistance); d++) {
                    wanted.add(current);
                    current = h.parent().get(current);
                }
            } else {
                List<Object> level = List.of(node);
                Set<Object> seen = new HashSet<>(Set.of(node));
                for (int d = 1; !level.isEmpty() && (maxDistance == null || d <= maxDistance); d++) {
                    List<Object> next = new ArrayList<>();
                    for (Object n : level) {
                        for (Object c : h.children().getOrDefault(n, List.of())) {
                            if (seen.add(c)) {
                                wanted.add(c);
                                next.add(c);
                            }
                        }
                    }
                    level = next;
                }
            }
        }
        List<Entity> out = new ArrayList<>();
        for (Entity r : rows) if (wanted.contains(node(r, p))) out.add(r);
        return out;
    }

    private List<Entity> traverse(List<Entity> rows, List<String> args) throws ODataApplicationException {
        if (args.size() < 4) throw bad("traverse(H, Q, p, preorder|postorder[, T]) expected");
        Hierarchy h = hierarchy(args.get(0), args.get(1), args.get(2));
        String p = args.get(2).strip();
        String mode = args.get(3).strip().toLowerCase(LOCALE);
        if (!mode.equals("preorder") && !mode.equals("postorder")) throw bad("traverse needs preorder or postorder");
        List<Object> roots = new ArrayList<>();
        if (args.size() > 4) {
            EdmStructuredType before = type;
            for (Entity s : run(rows, String.join(",", args.subList(4, args.size())))) {
                Object node = node(s, p);
                if (node != null) roots.add(node);
            }
            type = before;
        } else {
            for (Object n : h.order()) {
                Object parent = h.parent().get(n);
                if (parent == null || !h.parent().containsKey(parent)) roots.add(n);
            }
        }
        Map<Object, Integer> position = new LinkedHashMap<>();
        Set<Object> visited = new LinkedHashSet<>();
        for (Object root : roots) visit(root, h, mode.equals("preorder"), position, visited);
        Map<Object, List<Entity>> byNode = new HashMap<>();
        for (Entity r : rows) byNode.computeIfAbsent(node(r, p), k -> new ArrayList<>()).add(r);
        List<Entity> out = new ArrayList<>();
        for (Object n : position.keySet()) out.addAll(byNode.getOrDefault(n, List.of()));
        return out;
    }

    private static void visit(Object node, Hierarchy h, boolean preorder, Map<Object, Integer> position, Set<Object> visited) {
        if (!visited.add(node)) return; // cycles end here
        if (preorder) position.put(node, position.size());
        for (Object c : h.children().getOrDefault(node, List.of())) visit(c, h, preorder, position, visited);
        if (!preorder) position.put(node, position.size());
    }

    // --- text helpers ---------------------------------------------------------------------------------------------

    static String name(String step) {
        int paren = step.indexOf('(');
        return (paren < 0 ? step : step.substring(0, paren)).strip().toLowerCase(LOCALE);
    }

    private static String inner(String step) throws ODataApplicationException {
        int open = step.indexOf('(');
        if (open < 0 || !step.strip().endsWith(")")) throw bad("'" + step + "' needs arguments in parentheses");
        return step.substring(open + 1, step.lastIndexOf(')'));
    }

    static String withoutStrings(String s) {
        return s.replaceAll("'(?:[^']|'')*'", "''");
    }

    /** Splits at {@code sep} on nesting level 0, outside string literals. */
    static List<String> split(String s, char sep) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\'') {
                int j = i + 1;
                while (j < s.length()) {
                    if (s.charAt(j) == '\'') {
                        if (j + 1 < s.length() && s.charAt(j + 1) == '\'') {
                            j += 2;
                            continue;
                        }
                        break;
                    }
                    j++;
                }
                i = j;
                continue;
            }
            if (c == '(') depth++;
            if (c == ')') depth--;
            if (c == sep && depth == 0) {
                parts.add(s.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(s.substring(start));
        return parts;
    }

    private static ODataApplicationException bad(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
    }

    /** Links of {@code e} that a transformation created (for writers and row types). */
    static List<Link> applyLinks(Entity e) {
        return e.getNavigationLinks().stream().filter(l -> l instanceof ApplyLink).toList();
    }
}
