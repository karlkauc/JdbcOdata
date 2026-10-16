package at.jdbcodata.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_log")
public class AuditLogEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "admin_user", nullable = false, length = 100)
    private String adminUser;

    @Column(nullable = false, length = 50)
    private String action;

    @Column(name = "entity_type", nullable = false, length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(name = "entity_name", length = 200)
    private String entityName;

    @Column
    private String details;

    static AuditLogEntity of(String adminUser, String action, String entityType, String entityId, String entityName, String details) {
        AuditLogEntity e = new AuditLogEntity();
        e.occurredAt = Instant.now();
        e.adminUser = adminUser;
        e.action = action;
        e.entityType = entityType;
        e.entityId = entityId;
        e.entityName = entityName;
        e.details = details;
        return e;
    }

    AuditEntry toRecord() {
        return new AuditEntry(id, occurredAt, adminUser, action, entityType, entityId, entityName, details);
    }
}
