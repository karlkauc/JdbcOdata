package at.jdbcodata.audit;

import at.jdbcodata.common.CurrentUser;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Records configuration changes, sign-ins and query test runs (who, when, what). Details never contain secrets. */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final AuditLogRepository repository;
    private final int retentionDays;

    public AuditService(AuditLogRepository repository, @Value("${jdbcodata.audit.retention-days:0}") int retentionDays) {
        this.repository = repository;
        this.retentionDays = retentionDays;
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, UUID entityId, String entityName, Map<String, ?> details) {
        record(action, entityType, entityId == null ? null : entityId.toString(), entityName, details);
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(String action, String entityType, String entityId, String entityName, Map<String, ?> details) {
        repository.save(AuditLogEntity.of(CurrentUser.name(), action, entityType, entityId, entityName, json(details)));
    }

    /** For events without a signed-in user, such as a failed login. The name is untrusted, so it is cut to fit. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void recordAs(String username, String action, String entityType, Map<String, ?> details) {
        String name = username == null || username.isBlank() ? "unknown" : username;
        repository.save(AuditLogEntity.of(cut(name, 100), action, entityType, null, cut(name, 200), json(details)));
    }

    private static String json(Map<String, ?> details) {
        return details == null || details.isEmpty() ? null : JSON.writeValueAsString(details);
    }

    private static String cut(String value, int length) {
        return value.length() <= length ? value : value.substring(0, length);
    }

    /** Daily retention job; the first run happens one minute after start. 0 keeps everything. */
    @Scheduled(fixedDelay = 24 * 60 * 60 * 1000, initialDelay = 60 * 1000)
    @Transactional
    public void applyRetention() {
        if (retentionDays <= 0) return;
        int deleted = repository.deleteOlderThan(Instant.now().minus(Duration.ofDays(retentionDays)));
        if (deleted > 0) {
            log.info("Audit log retention: deleted {} records older than {} days", deleted, retentionDays);
            record("RETENTION", "AUDIT_LOG", (String) null, null, Map.of("deleted", deleted, "retentionDays", retentionDays));
        }
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
