package at.jdbcodata.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
    List<AuditLogEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

    @Modifying
    @Query("delete from AuditLogEntity e where e.occurredAt < :before")
    int deleteOlderThan(Instant before);

    List<AuditLogEntity> findAllByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, String entityId, Pageable pageable);
}
