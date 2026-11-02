package at.jdbcodata.cache;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryDefinitionsChangedEvent;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.ResultTable;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Per-user result cache: a persistent layer in the metadata DB (survives restarts, shared between instances) and a
 * Caffeine layer in front of it for hot entries. Keys include the effective DB user, so no user ever sees another
 * user's rows. Parallel misses for the same key run only one database execution.
 */
@Service
public class ResultCache {

    private static final Logger log = LoggerFactory.getLogger(ResultCache.class);

    public record Cached(ResultTable table, Instant createdAt, Instant expiresAt) {
        public long ageSeconds() {
            return Duration.between(createdAt, Instant.now()).getSeconds();
        }
    }

    private final CacheEntryRepository repository;
    private final QueryService queryService;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcOdataProperties.Cache config;
    private final Cache<CacheKey, Cached> memory;
    private final ConcurrentHashMap<CacheKey, Object> locks = new ConcurrentHashMap<>();
    private final CacheGeneration generation;
    private volatile Long seenGeneration;

    public ResultCache(CacheEntryRepository repository, QueryService queryService, AuditService audit,
                       TransactionTemplate tx, JdbcOdataProperties props, CacheGeneration generation) {
        this.repository = repository;
        this.queryService = queryService;
        this.audit = audit;
        this.tx = tx;
        this.config = props.cache();
        this.generation = generation;
        this.memory = Caffeine.newBuilder()
                .maximumWeight(Math.max(1L, config.memoryMb()) * 1024L * 1024L)
                .weigher((CacheKey k, Cached c) -> Math.max(1, estimateBytes(c.table())))
                .expireAfter(new Expiry<CacheKey, Cached>() {
                    @Override
                    public long expireAfterCreate(CacheKey key, Cached value, long currentTime) {
                        return Math.max(0, Duration.between(Instant.now(), value.expiresAt()).toNanos());
                    }

                    @Override
                    public long expireAfterUpdate(CacheKey key, Cached value, long currentTime, long currentDuration) {
                        return expireAfterCreate(key, value, currentTime);
                    }

                    @Override
                    public long expireAfterRead(CacheKey key, Cached value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                })
                .build();
    }

    /** Returns the cached result or runs {@code loader} once per key and stores its result. */
    public CachedResult getOrLoad(CacheKey key, Map<String, Object> params, Duration ttl, Supplier<ResultTable> loader) {
        Optional<Cached> hit = get(key);
        if (hit.isPresent()) {
            return new CachedResult(hit.get(), CacheStatus.HIT);
        }
        Object lock = locks.computeIfAbsent(key, k -> new Object());
        try {
            synchronized (lock) {
                hit = get(key);
                if (hit.isPresent()) {
                    return new CachedResult(hit.get(), CacheStatus.HIT);
                }
                ResultTable table = loader.get();
                return new CachedResult(put(key, params, table, ttl), CacheStatus.MISS);
            }
        } finally {
            locks.remove(key, lock);
        }
    }

    public record CachedResult(Cached cached, CacheStatus status) {
    }

    public Optional<Cached> get(CacheKey key) {
        Cached inMemory = memory.getIfPresent(key);
        if (inMemory != null) {
            return Optional.of(inMemory);
        }
        return tx.execute(status -> repository.findByQueryIdAndQueryVersionAndDbUserAndParamsHash(
                        key.queryId(), key.queryVersion(), key.dbUser(), key.paramsHash())
                .filter(e -> e.getExpiresAt().isAfter(Instant.now()))
                .map(e -> {
                    e.setLastAccessAt(Instant.now());
                    Cached c = new Cached(ResultTableCodec.decode(e.getPayload()), e.getCreatedAt(), e.getExpiresAt());
                    memory.put(key, c);
                    return c;
                }));
    }

    public Cached put(CacheKey key, Map<String, Object> params, ResultTable table, Duration ttl) {
        Instant now = Instant.now();
        Cached cached = new Cached(table, now, now.plus(ttl));
        byte[] payload = ResultTableCodec.encode(table);
        tx.executeWithoutResult(status -> {
            CacheEntryEntity e = repository.findByQueryIdAndQueryVersionAndDbUserAndParamsHash(
                    key.queryId(), key.queryVersion(), key.dbUser(), key.paramsHash()).orElseGet(CacheEntryEntity::new);
            e.setQueryId(key.queryId());
            e.setQueryVersion(key.queryVersion());
            e.setDbUser(key.dbUser());
            e.setParamsHash(key.paramsHash());
            e.setParamsJson(CacheKey.canonical(params));
            e.setPayload(payload);
            e.setRowCount(table.rows().size());
            e.setByteSize(payload.length);
            e.setCreatedAt(now);
            e.setExpiresAt(cached.expiresAt());
            e.setLastAccessAt(now);
            repository.save(e);
        });
        memory.put(key, cached);
        return cached;
    }

    @Transactional
    public int invalidateQuery(UUID queryId) {
        memory.asMap().keySet().removeIf(k -> k.queryId().equals(queryId));
        generation.bump();
        int n = repository.deleteByQuery(queryId);
        if (n > 0) log.debug("Invalidated {} cache entries of query {}", n, queryId);
        return n;
    }

    @Transactional
    public int invalidateQueryAndUser(UUID queryId, String dbUser) {
        String user = dbUser == null ? "" : dbUser.toLowerCase();
        memory.asMap().keySet().removeIf(k -> k.queryId().equals(queryId) && k.dbUser().equals(user));
        generation.bump();
        return repository.deleteByQueryAndUser(queryId, user);
    }

    @Transactional
    public long invalidateAll() {
        memory.invalidateAll();
        generation.bump();
        long n = repository.count();
        repository.deleteAllInBatch();
        return n;
    }

    /** Admin-triggered invalidation, written to the audit log. */
    @Transactional
    public int invalidateByAdmin(UUID queryId, String dbUser) {
        String name = queryService.findById(queryId).map(QueryDefinition::name).orElse(queryId.toString());
        int n = dbUser == null || dbUser.isBlank() ? invalidateQuery(queryId) : invalidateQueryAndUser(queryId, dbUser);
        audit.record("INVALIDATE_CACHE", "QUERY", queryId, name, Map.of("dbUser", dbUser == null ? "*" : dbUser, "entries", n));
        return n;
    }

    @Transactional
    public long invalidateAllByAdmin() {
        long n = invalidateAll();
        audit.record("INVALIDATE_CACHE", "CACHE", (String) null, "all", Map.of("entries", n));
        return n;
    }

    @EventListener
    public void onQueriesChanged(QueryDefinitionsChangedEvent event) {
        // versions are part of the key; drop in-memory entries so that deleted queries free memory immediately
        memory.invalidateAll();
    }

    /**
     * Invalidation on another instance: all in-memory results go, the persistent cache still answers. Entries are
     * deleted with the same transaction that raises the counter, so nothing outdated can be reloaded from it.
     */
    @Scheduled(fixedDelayString = "${jdbcodata.cluster.poll-interval-ms:5000}")
    public void followOtherInstances() {
        long current = generation.current();
        if (seenGeneration != null && seenGeneration != current) {
            memory.invalidateAll();
        }
        seenGeneration = current;
    }

    @Transactional(readOnly = true)
    public List<CacheEntryInfo> list(int limit) {
        Map<UUID, String> names = new java.util.HashMap<>();
        return repository.findAllByOrderByLastAccessAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 1000)))).stream()
                .map(e -> new CacheEntryInfo(e.getId(), e.getQueryId(),
                        names.computeIfAbsent(e.getQueryId(), id -> queryService.findById(id).map(QueryDefinition::name).orElse("(deleted)")),
                        e.getQueryVersion(), e.getDbUser(), e.getParamsJson(), e.getRowCount(), e.getByteSize(),
                        e.getCreatedAt(), e.getExpiresAt(), e.getLastAccessAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public CacheStats stats() {
        return new CacheStats(repository.count(), repository.totalBytes(), memory.estimatedSize(),
                (long) config.maxSizeMb() * 1024 * 1024);
    }

    public record CacheStats(long entries, long bytes, long memoryEntries, long maxBytes) {
    }

    /** Removes expired entries and enforces the size limit (least recently accessed first). */
    @Scheduled(fixedDelayString = "${jdbcodata.cache.cleanup-interval-ms:300000}", initialDelayString = "${jdbcodata.cache.cleanup-interval-ms:300000}")
    @Transactional(propagation = Propagation.REQUIRED)
    public void cleanup() {
        int expired = repository.deleteExpired(Instant.now());
        long maxBytes = (long) config.maxSizeMb() * 1024 * 1024;
        int evicted = 0;
        while (repository.totalBytes() > maxBytes) {
            List<UUID> oldest = repository.idsByLastAccess(PageRequest.of(0, 20));
            if (oldest.isEmpty()) break;
            repository.deleteAllByIdInBatch(oldest);
            evicted += oldest.size();
        }
        if (expired > 0 || evicted > 0) {
            log.info("Cache cleanup: {} expired, {} evicted for size", expired, evicted);
        }
    }

    static int estimateBytes(ResultTable table) {
        int cols = table.columns().size();
        return 64 + table.rows().size() * cols * 32;
    }
}
