package at.jdbcodata.odata;

import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryDefinitionsChangedEvent;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.etag.ServiceMetadataETagSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Caches Olingo {@link ServiceMetadata} per set of visible queries. The service document is personalised
 * (FR-35), so different callers may get different models; identical sets share one instance.
 */
@Component
public class EdmRegistry {

    private static final Logger log = LoggerFactory.getLogger(EdmRegistry.class);

    private final OData odata = OData.newInstance();
    private final Map<String, ServiceMetadata> cache = new ConcurrentHashMap<>();

    public ServiceMetadata serviceMetadata(List<QueryDefinition> visibleQueries) {
        String key = visibleQueries.stream().map(q -> q.name() + "@" + q.version() + "/" + q.updatedAt())
                .collect(Collectors.joining("|"));
        return cache.computeIfAbsent(key, k -> {
            log.debug("Building OData metadata with {} entity sets", visibleQueries.size());
            // The key changes with every query/relation/snapshot change, so it is a valid validator: Olingo
            // answers If-None-Match on $metadata and the service document with 304.
            String etag = "W/\"" + ResponseETag.hash(k.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + "\"";
            ServiceMetadataETagSupport etagSupport = new ServiceMetadataETagSupport() {
                @Override
                public String getMetadataETag() {
                    return etag;
                }

                @Override
                public String getServiceDocumentETag() {
                    return etag;
                }
            };
            return odata.createServiceMetadata(new DynamicEdmProvider(visibleQueries), List.of(), etagSupport);
        });
    }

    @EventListener
    public void onQueriesChanged(QueryDefinitionsChangedEvent event) {
        invalidate();
    }

    public void invalidate() {
        cache.clear();
    }

    public int size() {
        return cache.size();
    }
}
