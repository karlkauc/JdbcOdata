package at.jdbcodata.datasource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Immutable view of a configured target database. */
public record Datasource(
        UUID id,
        String name,
        String description,
        String jdbcUrl,
        String driverClass,
        String timezone,
        int defaultTimeoutSec,
        int maxRows,
        int maxConcurrent,
        boolean enabled,
        Instant createdAt,
        Instant updatedAt,
        Map<String, String> sessionSettings,
        Map<String, String> driverProperties,
        int poolSize,
        boolean passCaller) {

    /** Connections per database login unless configured otherwise. */
    public static final int DEFAULT_POOL_SIZE = 2;

    public Datasource {
        sessionSettings = sessionSettings == null ? Map.of() : Map.copyOf(sessionSettings);
        driverProperties = driverProperties == null ? Map.of() : Map.copyOf(driverProperties);
    }

    /** Without passing the caller. */
    public Datasource(UUID id, String name, String description, String jdbcUrl, String driverClass, String timezone,
                      int defaultTimeoutSec, int maxRows, int maxConcurrent, boolean enabled, Instant createdAt,
                      Instant updatedAt, Map<String, String> sessionSettings, Map<String, String> driverProperties,
                      int poolSize) {
        this(id, name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows, maxConcurrent, enabled,
                createdAt, updatedAt, sessionSettings, driverProperties, poolSize, false);
    }

    /** With the default pool size. */
    public Datasource(UUID id, String name, String description, String jdbcUrl, String driverClass, String timezone,
                      int defaultTimeoutSec, int maxRows, int maxConcurrent, boolean enabled, Instant createdAt,
                      Instant updatedAt, Map<String, String> sessionSettings, Map<String, String> driverProperties) {
        this(id, name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows, maxConcurrent, enabled,
                createdAt, updatedAt, sessionSettings, driverProperties, DEFAULT_POOL_SIZE);
    }

    /** Without session settings and driver properties. */
    public Datasource(UUID id, String name, String description, String jdbcUrl, String driverClass, String timezone,
                      int defaultTimeoutSec, int maxRows, int maxConcurrent, boolean enabled, Instant createdAt,
                      Instant updatedAt) {
        this(id, name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows, maxConcurrent, enabled,
                createdAt, updatedAt, Map.of(), Map.of());
    }
}
