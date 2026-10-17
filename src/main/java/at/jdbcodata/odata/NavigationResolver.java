package at.jdbcodata.odata;

import java.util.List;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.server.api.ODataApplicationException;

/**
 * Follows a navigation property of an entity during {@code $filter}/{@code $orderby} evaluation. The source query is
 * taken from the entity's type ({@code JdbcOdata.<Query>}). Implementations execute the target query at most once per
 * request; a to-one navigation returns zero or one entity.
 */
@FunctionalInterface
interface NavigationResolver {

    List<Entity> navigate(Entity source, String navigationProperty) throws ODataApplicationException;
}
