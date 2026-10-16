package at.jdbcodata.identity;

import java.time.Instant;
import java.util.UUID;

/** Technical account without the secret. */
public record FixedCredential(UUID id, String name, String description, UUID datasourceId, String datasourceName,
                              String dbUsername, Instant createdAt, Instant updatedAt) {
}
