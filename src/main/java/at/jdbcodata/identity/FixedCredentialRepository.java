package at.jdbcodata.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FixedCredentialRepository extends JpaRepository<FixedCredentialEntity, UUID> {
    Optional<FixedCredentialEntity> findByNameIgnoreCase(String name);

    List<FixedCredentialEntity> findAllByOrderByName();

    long countByDatasourceId(UUID datasourceId);
}
