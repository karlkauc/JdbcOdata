package at.jdbcodata.audit;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {
    List<AuditLogEntity> findAllByOrderByOccurredAtDesc(Pageable pageable);

    List<AuditLogEntity> findAllByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, String entityId, Pageable pageable);
}
