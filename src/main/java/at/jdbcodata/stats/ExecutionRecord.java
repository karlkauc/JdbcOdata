package at.jdbcodata.stats;

import at.jdbcodata.cache.CacheStatus;
import at.jdbcodata.query.ExecutionMode;
import java.time.Instant;
import java.util.UUID;

/** One execution of an OData request against a query. */
public record ExecutionRecord(
        Instant startedAt,
        Instant finishedAt,
        UUID queryId,
        String queryName,
        Integer queryVersion,
        UUID datasourceId,
        String datasourceName,
        String caller,
        String effectiveDbUser,
        ExecutionMode executionMode,
        ClientKind client,
        String userAgent,
        String clientIp,
        String parameters,
        String odataOptions,
        CacheStatus cacheStatus,
        Long dbDurationMs,
        long totalDurationMs,
        Integer rowCount,
        Long bytesSent,
        ExecutionStatus status,
        String errorMessage) {
}
