package at.jdbcodata.datasource;

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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Small, short-lived HikariCP pools per (datasource, db user, password hash). Repeated Excel refreshes by the
 * same user reuse a warm connection; pools idle for {@link #IDLE_EVICTION} are closed. Pass-through passwords are
 * kept only inside the pool configuration, never persisted.
 */
@Component
public class IdentityPoolManager {

    private static final Logger log = LoggerFactory.getLogger(IdentityPoolManager.class);
    private static final Duration IDLE_EVICTION = Duration.ofMinutes(5);

    private record PoolKey(UUID datasourceId, String username, String passwordHash) {
    }

    private static final class Pool {
        final HikariDataSource dataSource;
        volatile Instant lastUsed = Instant.now();

        Pool(HikariDataSource dataSource) {
            this.dataSource = dataSource;
        }
    }

    private final Map<PoolKey, Pool> pools = new ConcurrentHashMap<>();

    public Connection connect(Datasource ds, String username, String password) throws SQLException {
        PoolKey key = new PoolKey(ds.id(), username, sha256(password));
        Pool pool = pools.get(key);
        if (pool == null) {
            pool = pools.computeIfAbsent(key, k -> new Pool(createPool(ds, username, password)));
        }
        pool.lastUsed = Instant.now();
        return pool.dataSource.getConnection();
    }

    private HikariDataSource createPool(Datasource ds, String username, String password) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.jdbcUrl());
        cfg.setUsername(username);
        cfg.setPassword(password);
        if (ds.driverClass() != null && !ds.driverClass().isBlank()) {
            cfg.setDriverClassName(ds.driverClass());
        }
        cfg.setPoolName("jdbcodata-" + ds.name() + "-" + username);
        cfg.setMaximumPoolSize(2);
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

    public void closePoolsFor(UUID datasourceId) {
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
