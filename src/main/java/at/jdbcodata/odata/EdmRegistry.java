package at.jdbcodata.odata;

import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryDefinitionsChangedEvent;
import at.jdbcodata.query.QueryService;
import java.util.List;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ServiceMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Caches the Olingo {@link ServiceMetadata} built from all enabled queries with a schema snapshot. */
@Component
public class EdmRegistry {

    private static final Logger log = LoggerFactory.getLogger(EdmRegistry.class);

    private final QueryService queryService;
    private final OData odata = OData.newInstance();
    private volatile ServiceMetadata cached;

    public EdmRegistry(QueryService queryService) {
        this.queryService = queryService;
    }

    public ServiceMetadata serviceMetadata() {
        ServiceMetadata md = cached;
        if (md == null) {
            synchronized (this) {
                md = cached;
                if (md == null) {
                    List<QueryDefinition> queries = queryService.findAllEnabled().stream()
                            .filter(QueryDefinition::hasSnapshot)
                            .toList();
                    md = odata.createServiceMetadata(new DynamicEdmProvider(queries), List.of());
                    cached = md;
                    log.debug("Built OData metadata with {} entity sets", queries.size());
                }
            }
        }
        return md;
    }

    @EventListener
    public void onQueriesChanged(QueryDefinitionsChangedEvent event) {
        invalidate();
    }

    public void invalidate() {
        cached = null;
    }
}
