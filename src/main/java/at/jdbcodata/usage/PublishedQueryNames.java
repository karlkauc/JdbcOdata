package at.jdbcodata.usage;

import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

/** Names of the enabled queries, reloaded once a minute: anything else in a URL is not worth a database lookup. */
class PublishedQueryNames {

    private static final long MAX_AGE_NANOS = Duration.ofMinutes(1).toNanos();

    private final QueryService queries;
    private volatile Set<String> names;
    private volatile long loadedAt;

    PublishedQueryNames(QueryService queries) {
        this.queries = queries;
    }

    boolean contains(String name) {
        if (names == null || System.nanoTime() - loadedAt > MAX_AGE_NANOS) {
            names = queries.findAllEnabled().stream().map(QueryDefinition::name).collect(Collectors.toUnmodifiableSet());
            loadedAt = System.nanoTime();
        }
        return names.contains(name);
    }
}
