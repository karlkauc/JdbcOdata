package at.jdbcodata.odata;

import java.util.List;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.uri.UriParameter;
import org.apache.olingo.server.api.uri.queryoption.AliasQueryOption;

/**
 * What expression evaluation needs from the request: following navigation properties (the source query is taken
 * from the entity's type {@code JdbcOdata.<Query>}; implementations execute each target at most once per request,
 * a to-one navigation yields zero or one entity) and parameter aliases ({@code $filter=Name eq @n&@n='Wien'}).
 */
@FunctionalInterface
interface EvaluationContext {

    List<Entity> navigate(Entity source, String navigationProperty) throws ODataApplicationException;

    /** The alias option {@code @name}, or {@code null} if the request does not define it. */
    default AliasQueryOption alias(String name) {
        return null;
    }

    /** {@code $root/EntitySet(key)}: the entity with this key, 404 if there is none. */
    default Entity lookup(String entitySet, List<UriParameter> keys) throws ODataApplicationException {
        throw new ODataApplicationException("$root not supported here", 501, java.util.Locale.ENGLISH);
    }
}
