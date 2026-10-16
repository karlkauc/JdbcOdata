package at.jdbcodata.identity;

import java.time.Instant;
import java.util.UUID;

/** Stored credential without the secret. */
public record StoredCredential(UUID id, UUID serviceUserId, UUID datasourceId, String datasourceName, String dbUsername,
                               String keyId, Instant updatedAt) {
}
