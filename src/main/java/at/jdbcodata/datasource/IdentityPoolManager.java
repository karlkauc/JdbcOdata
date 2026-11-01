package at.jdbcodata.datasource;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Small, short-lived HikariCP pools per (datasource, db user, password hash, session settings). Session settings
 * are applied once per connection by {@code connectionInitSql}, so a query with overrides gets its own pool instead
 * of resetting the session after each execution. Repeated Excel refreshes by the same user reuse a warm
 * connection; pools idle for {@link #IDLE_EVICTION} are closed. Pass-through passwords are kept only inside the pool
 * configuration, never persisted.
 */
@Component
public class IdentityPoolManager {

    private static final Logger log = LoggerFactory.getLogger(IdentityPoolManager.class);
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(5);
    private static final Duration REJECTED_LOGIN_MEMORY = Duration.ofMinutes(5);

    private record PoolKey(UUID datasourceId, String username, String passwordHash, Map<String, String> settings) {
    }

    private record Login(UUID datasourceId, String username, String passwordHash) {
    }

    private static final class Pool {
        final HikariDataSource dataSource;
        volatile Instant lastUsed = Instant.now();

        Pool(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }
    }

    private final Map<PoolKey, Pool> pools = new ConcurrentHashMap<>();

    /**
     * Logins a database just rejected. Each request checks the caller against every datasource, so without this a
     * user with accounts on only some of them would fail a slow login on the others every time, which can also lock
     * those accounts. It only ever saves an attempt: another password is another entry.
     */
    private final Cache<Login, Boolean> rejected = Caffeine.newBuilder()
            .expireAfterWrite(REJECTED_LOGIN_MEMORY).maximumSize(10_000).build();

    /** A connection with the session settings of the datasource. */
    public Connection connect(Datasource ds, String username, String password) throws SQLException {
        return connect(ds, ds.sessionSettings(), username, password);
    }

    /** A connection with the given (effective) session settings, see {@link SessionSettings#effective}. */
    public Connection connect(Datasource ds, Map<String, String> sessionSettings, String username, String password)
            throws SQLException {
        Map<String, String> settings = new TreeMap<>(sessionSettings == null ? Map.of() : sessionSettings);
        PoolKey key = new PoolKey(ds.id(), username, sha256(password), Map.copyOf(settings));
        Pool pool = pools.get(key);
        if (pool == null) {
            pool = pools.computeIfAbsent(key, k -> new Pool(createPool(ds, settings, username, password)));
        }
        pool.lastUsed = Instant.now();
        return pool.dataSource.getConnection();
    }

    private HikariDataSource createPool(Datasource ds, Map<String, String> settings, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.jdbcUrl());
        cfg.setUsername(username);
        cfg.setPassword(password);
        if (ds.driverClass() != null && !ds.driverClass().isBlank()) {
            cfg.setDriverClassName(ds.driverClass());
        }
        ds.driverProperties().forEach(cfg::addDataSourceProperty);
        String initSql = SessionSettings.initSql(ds.jdbcUrl(), settings);
        if (initSql != null) {
            cfg.setConnectionInitSql(initSql);
            // commits the init statement: with auto-commit off, PostgreSQL would undo set_config on the first rollback
            cfg.setIsolateInternalQueries(true);
        }
        cfg.setPoolName("jdbcodata-" + ds.name() + "-" + username + (settings.isEmpty() ? "" : "-" + sha256(settings.toString()).substring(0, 8)));
        cfg.setMaximumPoolSize(ds.poolSize());
        cfg.setMinimumIdle(0);
        cfg.setIdleTimeout(Duration.ofMinutes(1).toMillis());
        cfg.setConnectionTimeout(Duration.ofSeconds(15).toMillis());
        cfg.setReadOnly(true);
        cfg.setAutoCommit(false);
        cfg.setInitializationFailTimeout(1); // fail fast on wrong credentials
        try {
            HikariDataSource hds = new HikariDataSource(cfg);
            log.debug("Opened pool {}", cfg.getPoolName());
            return hds;
        } catch (RuntimeException e) {
            throw DbErrorClassifier.translate(e, ds.name());
        }
    }

    public boolean recentlyRejected(Datasource ds, String username, String password) {
        return rejected.getIfPresent(new Login(ds.id(), username, sha256(password))) != null;
    }

    public void rememberRejected(Datasource ds, String username, String password) {
        rejected.put(new Login(ds.id(), username, sha256(password)), Boolean.TRUE);
    }

    public void closePoolsFor(UUID datasourceId) {
        rejected.asMap().keySet().removeIf(login -> login.datasourceId().equals(datasourceId));
        pools.entrySet().removeIf(entry -> {
            if (entry.getKey().datasourceId().equals(datasourceId)) {
                close(entry.getValue());
                return true;
            }
            return false;
        });
    }

    @Scheduled(fixedDelay = 60_000)
    public void evictIdlePools() {
        Instant cutoff = Instant.now().minus(IDLE_EVICTION);
        pools.entrySet().removeIf(entry -> {
            Pool p = entry.getValue();
            if (p.lastUsed.isBefore(cutoff) && p.dataSource.getHikariPoolMXBean().getActiveConnections() == 0) {
                close(p);
                return true;
            }
            return false;
        });
    }

    public int activePoolCount() {
        return pools.size();
    }

    public int activeConnections() {
        return pools.values().stream().mapToInt(p -> p.dataSource.getHikariPoolMXBean().getActiveConnections()).sum();
    }

    public int pendingConnections() {
        return pools.values().stream().mapToInt(p -> p.dataSource.getHikariPoolMXBean().getThreadsAwaitingConnection()).sum();
    }

    @PreDestroy
    public void closeAll() {
        pools.values().forEach(this::close);
        pools.clear();
    }

    private void close(Pool p) {
        try {
            log.debug("Closing pool {}", p.dataSource.getPoolName());
            p.dataSource.close();
        } catch (RuntimeException e) {
            log.warn("Failed to close pool {}", p.dataSource.getPoolName(), e);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
