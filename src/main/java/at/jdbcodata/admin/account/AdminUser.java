package at.jdbcodata.admin.account;

import java.time.Instant;
import java.util.UUID;

public record AdminUser(UUID id, String username, AdminRole role, boolean enabled, Instant createdAt, Instant updatedAt,
                        Instant lastLoginAt) {
}
