package at.jdbcodata.cache;

import java.time.Instant;
import java.util.UUID;

public record CacheEntryInfo(UUID id, UUID queryId, String queryName, int queryVersion, String dbUser, String params,
                             int rowCount, int byteSize, Instant createdAt, Instant expiresAt, Instant lastAccessAt) {
}
