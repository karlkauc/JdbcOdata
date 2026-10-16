package at.jdbcodata.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StoredCredentialRepository extends JpaRepository<StoredCredentialEntity, UUID> {
    Optional<StoredCredentialEntity> findByServiceUserIdAndDatasourceId(UUID serviceUserId, UUID datasourceId);

    List<StoredCredentialEntity> findAllByServiceUserId(UUID serviceUserId);

    long countByDatasourceId(UUID datasourceId);
}
