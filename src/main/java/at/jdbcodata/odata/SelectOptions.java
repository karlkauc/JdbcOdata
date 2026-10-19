package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.olingo.commons.api.data.Annotation;
import org.apache.olingo.commons.api.data.ComplexValue;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntityCollection;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.edm.EdmComplexType;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmProperty;
import org.apache.olingo.commons.api.edm.EdmStructuredType;
import org.apache.olingo.commons.api.edm.EdmType;
import org.apache.olingo.commons.api.edm.FullQualifiedName;
import org.apache.olingo.commons.api.edm.constants.EdmTypeKind;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;
import org.apache.olingo.server.api.uri.queryoption.FilterOption;
import org.apache.olingo.server.api.uri.queryoption.OrderByOption;
import org.apache.olingo.server.api.uri.queryoption.SearchOption;
import org.apache.olingo.server.api.uri.queryoption.SkipOption;
import org.apache.olingo.server.api.uri.queryoption.TopOption;

/**
 * {@code $select} with options (OData 4.01) on collection-valued properties:
 * {@code $select=Tags($filter=$this ne 'alt';$orderby=$this;$top=2;$count=true)},
 * {@code Positionen($filter=Betrag gt 100;$orderby=Betrag desc;$select=Artikel,Betrag)}. {@link QueryRewriter} moved
 * the options into custom options; they are applied in memory to the delivered (and expanded) entities, right
 * before serialisation: search, filter, orderby, count, skip, top, then the nested select. {@code $this} is the
 * element of a primitive collection; the number of elements before skip/top becomes {@code Property@odata.count}.
 */
final class SelectOptions {

    private static final Locale LOCALE = Locale.ENGLISH;
    private static final Set<String> ALLOWED = Set.of("$filter", "$search", "$orderby", "$skip", "$top", "$count", "$select");
    private static final int MAX_DEPTH = 3;

    private final Edm edm;
    private final OData odata;
    private final Map<String, AliasQueryOption> aliases;

    SelectOptions(Edm edm, OData odata, Map<String, AliasQueryOption> aliases) {
        this.edm = edm;
        this.odata = odata;
        this.aliases = aliases;
    }

    static boolean present(Map<String, String> custom) {
        return custom.keySet().stream().anyMatch(k -> k.startsWith(QueryRewriter.SELECT_OPTION));
    }

    /** Applies every select option of the request to {@code rows} (and the expanded entities below them). */
    void apply(List<Entity> rows, Map<String, String> custom) throws ODataApplicationException {
        List<String> keys = new ArrayList<>();
        for (String k : custom.keySet()) if (k.startsWith(QueryRewriter.SELECT_OPTION)) keys.add(k);
        Collections.sort(keys);
        for (String k : keys) {
            String text = custom.get(k);
            int open = text.indexOf('(');
            if (open < 0 || !text.endsWith(")")) continue;
            List<String> path = List.of(text.substring(0, open).split("/"));
            String options = text.substring(open + 1, text.length() - 1);
            List<Entity> owners = rows;
            for (String nav : path.subList(0, path.size() - 1)) owners = expanded(owners, nav);
            String property = path.get(path.size() - 1);
            Map<Entity, Boolean> done = new IdentityHashMap<>();
            for (Entity e : owners) {
                if (done.put(e, Boolean.TRUE) != null) continue; // the same related entity below several parents
                Property p = e.getProperty(property);
                if (p == null) continue;
                transform(p, elementType(e, p), options, 1);
            }
        }
    }

    private static List<Entity> expanded(List<Entity> owners, String nav) {
        List<Entity> out = new ArrayList<>();
        for (Entity e : owners) {
            Link l = e.getNavigationLink(nav);
            if (l == null) continue;
            if (l.getInlineEntitySet() != null) out.addAll(l.getInlineEntitySet().getEntities());
            else if (l.getInlineEntity() != null) out.add(l.getInlineEntity());
        }
        return out;
    }

    /** Element type of collection property {@code p}: from the declared entity type, else from the property. */
    private EdmType elementType(Entity owner, Property p) throws ODataApplicationException {
        EdmProperty declared = null;
        if (owner.getType() != null) {
            EdmEntityType type = edm.getEntityType(new FullQualifiedName(owner.getType()));
            if (type != null && type.getStructuralProperty(p.getName()) != null) declared = type.getStructuralProperty(p.getName());
        }
        if (declared != null) {
            if (!declared.isCollection()) throw notCollection(p.getName());
            return declared.getType();
        }
        if (!p.isCollection()) throw notCollection(p.getName());
        if (p.getValueType() == ValueType.COLLECTION_COMPLEX) {
            EdmComplexType complex = p.getType() == null ? null : edm.getComplexType(new FullQualifiedName(p.getType()));
            if (complex == null) throw bad("Unknown element type of '" + p.getName() + "'");
            return complex;
        }
        return org.apache.olingo.commons.core.edm.primitivetype.EdmPrimitiveTypeFactory.getInstance(
                org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind.valueOfFQN(p.getType() == null ? "Edm.String" : p.getType()));
    }

    private static ODataApplicationException notCollection(String name) {
        return bad("$select options apply to collection-valued properties only; '" + name + "' is none (use $expand for navigation properties)");
    }

    /** Parsed options of one select item, for one element type. */
    private record Options(SearchOption search, FilterOption filter, OrderByOption orderBy, SkipOption skip, TopOption top,
                           boolean count, List<String> select, Map<String, String> nested) {
    }

    private void transform(Property p, EdmType elementType, String text, int depth) throws ODataApplicationException {
        if (depth > MAX_DEPTH) throw bad("$select options are nested too deeply (at most " + MAX_DEPTH + " levels)");
        if (!(p.getValue() instanceof List<?> elements)) return; // null collection
        Options o = parse(text, elementType, p.getName());
        Map<Entity, Object> byRow = new IdentityHashMap<>();
        List<Entity> rows = new ArrayList<>(elements.size());
        for (Object element : elements) {
            Entity row = CollectionValues.row(element);
            byRow.put(row, element);
            rows.add(row);
        }
        EntityCollection result = QueryOptionsEvaluator.apply(rows, o.search(), o.filter(), o.orderBy(), o.skip(), o.top(), null);
        List<Object> kept = new ArrayList<>(result.getEntities().size());
        for (Entity row : result.getEntities()) {
            Object element = byRow.get(row);
            if (element instanceof ComplexValue cv && (o.select() != null || !o.nested().isEmpty())) {
                element = project(cv, (EdmComplexType) elementType, o, depth);
            }
            kept.add(element);
        }
        p.setValue(p.getValueType(), kept);
        if (o.count()) {
            Annotation a = new Annotation();
            a.setTerm(DynamicResultWriter.ODATA_COUNT);
            a.setType("Int64"); // Olingo resolves the type by its simple name
            a.setValue(ValueType.PRIMITIVE, (long) result.getCount());
            p.getAnnotations().removeIf(x -> DynamicResultWriter.ODATA_COUNT.equals(x.getTerm()));
            p.getAnnotations().add(a);
        }
    }

    /** Nested {@code $select} on a complex element: only the selected fields, those with options transformed too. */
    private ComplexValue project(ComplexValue cv, EdmComplexType type, Options o, int depth) throws ODataApplicationException {
        ComplexValue out = new ComplexValue();
        for (Property f : cv.getValue()) {
            if (o.select() != null && !o.select().contains(f.getName())) continue;
            Property copy = new Property(f.getType(), f.getName(), f.getValueType(),
                    f.getValue() instanceof List<?> l ? new ArrayList<>(l) : f.getValue());
            String nested = o.nested().get(f.getName());
            if (nested != null) transform(copy, type.getStructuralProperty(f.getName()).getType(), nested, depth + 1);
            out.getValue().add(copy);
        }
        return out;
    }

    private Options parse(String text, EdmType elementType, String property) throws ODataApplicationException {
        boolean primitive = elementType.getKind() == EdmTypeKind.PRIMITIVE;
        EdmStructuredType type = primitive ? thisType(elementType) : (EdmStructuredType) elementType;
        SearchOption search = null;
        FilterOption filter = null;
        OrderByOption orderBy = null;
        SkipOption skip = null;
        TopOption top = null;
        boolean count = false;
        List<String> select = null;
        Map<String, String> nested = new LinkedHashMap<>();
        for (String raw : QueryRewriter.split(text, ';')) {
            String option = raw.strip();
            if (option.isEmpty()) continue;
            int eq = option.indexOf('=');
            String name = (eq < 0 ? option : option.substring(0, eq)).strip().toLowerCase(Locale.ROOT);
            String value = eq < 0 ? "" : option.substring(eq + 1).strip();
            if (!ALLOWED.contains(name)) {
                throw bad("Option " + name + " is not allowed in $select of '" + property + "' (" + String.join(", ", ALLOWED.stream().sorted().toList()) + ")");
            }
            switch (name) {
                case "$filter" -> filter = parseExpression(value, type, true);
                case "$orderby" -> orderBy = parseExpression(value, type, false);
                case "$search" -> {
                    try {
                        search = new org.apache.olingo.server.core.uri.queryoption.SearchOptionImpl()
                                .setSearchExpression(SearchSyntax.parse(value));
                    } catch (IllegalArgumentException e) {
                        throw bad("$search in $select of '" + property + "': " + e.getMessage());
                    }
                }
                case "$skip" -> skip = new org.apache.olingo.server.core.uri.queryoption.SkipOptionImpl().setValue(number(name, value));
                case "$top" -> top = new org.apache.olingo.server.core.uri.queryoption.TopOptionImpl().setValue(number(name, value));
                case "$count" -> {
                    if (!value.equals("true") && !value.equals("false")) throw bad("$count must be true or false");
                    count = value.equals("true");
                }
                case "$select" -> {
                    if (primitive) throw bad("$select inside $select applies to complex elements only ('" + property + "')");
                    select = new ArrayList<>();
                    for (String item : QueryRewriter.split(value, ',')) {
                        String s = item.strip();
                        if (s.equals("*")) {
                            select = null;
                            break;
                        }
                        int open = s.indexOf('(');
                        String field = open < 0 ? s : s.substring(0, open).strip();
                        EdmProperty declared = ((EdmStructuredType) elementType).getStructuralProperty(field);
                        if (declared == null) throw bad("'" + field + "' is no property of the elements of '" + property + "'");
                        select.add(field);
                        if (open >= 0) {
                            if (!s.endsWith(")")) throw bad("Unbalanced parentheses in $select of '" + property + "'");
                            if (!declared.isCollection()) throw notCollection(field);
                            nested.put(field, s.substring(open + 1, s.length() - 1));
                        }
                    }
                }
                default -> throw bad("Option " + name + " is not allowed in $select");
            }
        }
        return new Options(search, filter, orderBy, skip, top, count, select, nested);
    }

    /** A row type with the single property {@link CollectionValues#THIS} of the element type (for {@code $this}). */
    private EdmStructuredType thisType(EdmType elementType) {
        Entity sample = new Entity();
        sample.addProperty(new Property(elementType.getFullQualifiedName().getFullQualifiedNameAsString(),
                CollectionValues.THIS, ValueType.PRIMITIVE, null));
        return RowType.of(List.of(sample), null, edm);
    }

    @SuppressWarnings("unchecked")
    private <T> T parseExpression(String text, EdmStructuredType type, boolean filter) throws ODataApplicationException {
        ExpressionRewriter rewriter = new ExpressionRewriter(type);
        String rewritten = rewriter.expression(replaceThis(text));
        if (!rewriter.cases().isEmpty()) throw new ODataApplicationException("case() inside $select options is not supported",
                HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), LOCALE);
        try {
            org.apache.olingo.server.core.uri.parser.UriTokenizer tokenizer = new org.apache.olingo.server.core.uri.parser.UriTokenizer(rewritten);
            Object result = filter
                    ? new org.apache.olingo.server.core.uri.parser.FilterParser(edm, odata).parse(tokenizer, type, null, aliases)
                    : new org.apache.olingo.server.core.uri.parser.OrderByParser(edm, odata).parse(tokenizer, type, null, aliases);
            if (!tokenizer.next(org.apache.olingo.server.core.uri.parser.UriTokenizer.TokenKind.EOF)) {
                throw bad("Unexpected text in '" + text + "'");
            }
            return (T) result;
        } catch (org.apache.olingo.server.core.uri.parser.UriParserException
                 | org.apache.olingo.server.core.uri.validator.UriValidationException e) {
            throw bad("Cannot parse '" + text + "' in $select: " + e.getMessage());
        }
    }

    /** {@code $this} (outside literals) → the property that holds a primitive element. */
    static String replaceThis(String text) {
        StringBuilder out = new StringBuilder();
        boolean literal = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') literal = !literal;
            if (!literal && text.startsWith("$this", i)
                    && (i + 5 >= text.length() || !Character.isLetterOrDigit(text.charAt(i + 5)) && text.charAt(i + 5) != '_')) {
                out.append(CollectionValues.THIS);
                i += 4;
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    private static int number(String option, String value) throws ODataApplicationException {
        try {
            int n = Integer.parseInt(value);
            if (n < 0) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw bad(option + " must be a non-negative integer");
        }
    }

    private static ODataApplicationException bad(String message) {
        return new ODataApplicationException(message, HttpStatusCode.BAD_REQUEST.getStatusCode(), LOCALE);
    }
}
