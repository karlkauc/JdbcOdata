package at.jdbcodata.query;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueryGrantRepository extends JpaRepository<QueryGrantEntity, UUID> {
    List<QueryGrantEntity> findAllByQueryId(UUID queryId);

    List<QueryGrantEntity> findAllByPrincipalId(UUID principalId);

    void deleteAllByQueryId(UUID queryId);
}
