package at.jdbcodata.stats;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLogEntity, UUID> {
    @Modifying
    @Query("delete from ExecutionLogEntity e where e.startedAt < :before")
    int deleteOlderThan(Instant before);
}
