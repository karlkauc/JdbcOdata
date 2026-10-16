package at.jdbcodata.audit;

import java.time.Instant;
import java.util.UUID;

public record AuditEntry(UUID id, Instant occurredAt, String adminUser, String action, String entityType,
                         String entityId, String entityName, String details) {
}
