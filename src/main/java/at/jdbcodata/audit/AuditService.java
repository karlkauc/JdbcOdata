package at.jdbcodata.audit;

import at.jdbcodata.common.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Records configuration changes (who, when, what). Details never contain secrets. */
@Service
public class AuditService {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditLogRepository repository;

    public AuditService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, UUID entityId, String entityName, Map<String, ?> details) {
        record(action, entityType, entityId == null ? null : entityId.toString(), entityName, details);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, String entityId, String entityName, Map<String, ?> details) {
        String json = details == null || details.isEmpty() ? null : JSON.writeValueAsString(details);
        repository.save(AuditLogEntity.of(CurrentUser.name(), action, entityType, entityId, entityName, json));
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> latest(int limit) {
        return repository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 1000))))
                .stream().map(AuditLogEntity::toRecord).toList();
    }

    @Transactional(readOnly = true)
    public List<AuditEntry> forEntity(String entityType, String entityId, int limit) {
        return repository.findAllByEntityTypeAndEntityIdOrderByOccurredAtDesc(entityType, entityId,
                PageRequest.of(0, Math.max(1, Math.min(limit, 1000)))).stream().map(AuditLogEntity::toRecord).toList();
    }
}
