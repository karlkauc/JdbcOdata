package at.jdbcodata.datasource;

import java.time.Instant;
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
        Instant updatedAt) {
}
