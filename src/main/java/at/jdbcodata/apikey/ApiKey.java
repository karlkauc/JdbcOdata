package at.jdbcodata.apikey;

import at.jdbcodata.admin.account.AdminRole;
import java.time.Instant;
import java.util.UUID;

/** Key without its secret: only a prefix to recognise it remains after creation. */
public record ApiKey(UUID id, String name, String keyPrefix, UUID serviceUserId, AdminRole adminRole, String createdBy,
                     Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {

    public boolean activeAt(Instant now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    /** Name recorded for requests with this key, on feeds and on the REST API. */
    public String caller() {
        return ApiKeyService.CALLER_PREFIX + name;
    }
}
