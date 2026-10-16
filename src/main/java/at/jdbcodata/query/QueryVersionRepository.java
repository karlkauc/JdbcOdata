package at.jdbcodata.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueryVersionRepository extends JpaRepository<QueryVersionEntity, UUID> {
    List<QueryVersionEntity> findAllByQueryIdOrderByVersionDesc(UUID queryId);

    Optional<QueryVersionEntity> findByQueryIdAndVersion(UUID queryId, int version);

    boolean existsByQueryIdAndVersion(UUID queryId, int version);
}
