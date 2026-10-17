package at.jdbcodata.odata;

import java.util.ArrayList;
import java.util.List;
import org.apache.olingo.commons.api.edm.Edm;
import org.apache.olingo.commons.api.edm.EdmEntityContainer;
import org.apache.olingo.commons.api.edm.EdmEntitySet;
import org.apache.olingo.commons.api.edm.EdmEntityType;
import org.apache.olingo.commons.api.edm.EdmFunctionImport;
import org.apache.olingo.commons.api.edm.EdmKeyPropertyRef;
import org.apache.olingo.commons.api.edm.EdmNavigationProperty;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;

/**
 * Key-as-segment (OData 4.01): {@code Kunden/1/Umsaetze/5} becomes {@code Kunden(1)/Umsaetze(5)} before Olingo 5,
 * which only knows parentheses, parses the path. A segment after a collection is a key when it is no system segment
 * ({@code $count}, {@code $ref}, …), no qualified name (cast, bound operation) and no property or navigation of the
 * entity type. Only single-part keys can be written this way; string keys are quoted.
 */
final class KeySegmentRewriter {

    private KeySegmentRewriter() {
    }

    /** {@code path} is the raw OData path below the service root, starting with {@code /}. */
    static String rewrite(String path, Edm edm) {
        if (edm == null || path == null || path.length() < 2 || !path.startsWith("/")) return path;
        String[] segments = path.substring(1).split("/", -1);
        EdmEntityContainer container = edm.getEntityContainer();
        if (container == null) return path;
        String first = QueryRewriter.decode(segments[0]);
        if (first == null) return path;
        int paren = first.indexOf('(');
        String name = paren < 0 ? first : first.substring(0, paren);
        EdmEntityType type;
        boolean collection;
        EdmEntitySet set = container.getEntitySet(name);
        EdmFunctionImport fn = set == null ? container.getFunctionImport(name) : null;
        if (set != null) {
            type = set.getEntityType();
            collection = paren < 0;
        } else if (fn != null && fn.getReturnedEntitySet() != null && paren >= 0) {
            type = fn.getReturnedEntitySet().getEntityType();
            collection = first.indexOf('(', first.indexOf(')')) < 0; // GetX(p=1) vs GetX(p=1)(17)
        } else {
            return path;
        }
        List<String> out = new ArrayList<>();
        out.add(segments[0]);
        boolean changed = false;
        int i = 1;
        for (; i < segments.length; i++) {
            String raw = segments[i];
            String seg = QueryRewriter.decode(raw);
            if (seg == null || seg.isEmpty() || seg.startsWith("$") || seg.contains("(") || seg.contains(".")) break;
            if (collection) {
                if (type.getProperty(seg) != null) break;
                String key = keyLiteral(type, seg);
                if (key == null) break;
                out.set(out.size() - 1, out.get(out.size() - 1) + "(" + key + ")");
                changed = true;
                collection = false;
                continue;
            }
            EdmNavigationProperty nav = type.getNavigationProperty(seg);
            if (nav == null) break;
            out.add(raw);
            type = nav.getType();
            collection = nav.isCollection();
        }
        if (!changed) return path;
        for (; i < segments.length; i++) out.add(segments[i]);
        return "/" + String.join("/", out);
    }

    /** The key in parentheses syntax, encoded for the path; {@code null} for composite keys. */
    private static String keyLiteral(EdmEntityType type, String value) {
        List<EdmKeyPropertyRef> keys = type.getKeyPropertyRefs();
        if (keys.size() != 1) return null;
        boolean string = keys.get(0).getProperty().getType().getFullQualifiedName()
                .equals(EdmPrimitiveTypeKind.String.getFullQualifiedName());
        String literal = string ? "'" + value.replace("'", "''") + "'" : value;
        return QueryRewriter.encode(literal).replace("/", "%2F");
    }

    /**
     * Does the (already rewritten) path address one entity, e.g. {@code Kunden(1)}, {@code Umsatz(1)/Kunde} or
     * {@code GetUmsatz(p_jahr=2024)(17)}? Used to treat {@code $compute} there, where Olingo forbids {@code $apply}.
     */
    static boolean addressesSingleEntity(String path, Edm edm) {
        if (edm == null || path == null || path.length() < 2 || !path.startsWith("/")) return false;
        EdmEntityContainer container = edm.getEntityContainer();
        if (container == null) return false;
        String[] segments = path.substring(1).split("/", -1);
        String first = QueryRewriter.decode(segments[0]);
        if (first == null) return false;
        int paren = first.indexOf('(');
        String name = paren < 0 ? first : first.substring(0, paren);
        EdmEntityType type;
        boolean single;
        EdmEntitySet set = container.getEntitySet(name);
        EdmFunctionImport fn = set == null ? container.getFunctionImport(name) : null;
        if (set != null) {
            type = set.getEntityType();
            single = paren >= 0;
        } else if (fn != null && fn.getReturnedEntitySet() != null && paren >= 0) {
            type = fn.getReturnedEntitySet().getEntityType();
            single = first.indexOf('(', first.indexOf(')')) >= 0;
        } else {
            return false;
        }
        for (int i = 1; i < segments.length; i++) {
            String seg = QueryRewriter.decode(segments[i]);
            if (seg == null || seg.isEmpty() || !single) return false;
            int p = seg.indexOf('(');
            EdmNavigationProperty nav = type.getNavigationProperty(p < 0 ? seg : seg.substring(0, p));
            if (nav == null) return false; // property, $value, $ref, …
            type = nav.getType();
            single = !nav.isCollection() || p >= 0;
        }
        return single;
    }

    /** Entity type of the resource the (rewritten) path addresses, or null (properties, $metadata, …). */
    static EdmEntityType targetType(String path, Edm edm) {
        if (edm == null || path == null || path.length() < 2 || !path.startsWith("/")) return null;
        EdmEntityContainer container = edm.getEntityContainer();
        if (container == null) return null;
        String[] segments = path.substring(1).split("/", -1);
        String first = QueryRewriter.decode(segments[0]);
        if (first == null) return null;
        int paren = first.indexOf('(');
        String name = paren < 0 ? first : first.substring(0, paren);
        EdmEntityType type;
        EdmEntitySet set = container.getEntitySet(name);
        EdmFunctionImport fn = set == null ? container.getFunctionImport(name) : null;
        if (set != null) type = set.getEntityType();
        else if (fn != null && fn.getReturnedEntitySet() != null) type = fn.getReturnedEntitySet().getEntityType();
        else return null;
        for (int i = 1; i < segments.length; i++) {
            String seg = QueryRewriter.decode(segments[i]);
            if (seg == null || seg.isEmpty() || seg.startsWith("$")) break;
            int p = seg.indexOf('(');
            EdmNavigationProperty nav = type.getNavigationProperty(p < 0 ? seg : seg.substring(0, p));
            if (nav == null) return null;
            type = nav.getType();
        }
        return type;
    }
}
