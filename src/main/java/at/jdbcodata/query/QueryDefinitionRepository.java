package at.jdbcodata.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface QueryDefinitionRepository extends JpaRepository<QueryDefinitionEntity, UUID> {
    Optional<QueryDefinitionEntity> findByName(String name);

    List<QueryDefinitionEntity> findAllByEnabledTrueOrderByName();

    List<QueryDefinitionEntity> findAllByOrderByName();

    long countByDatasourceId(UUID datasourceId);

    List<QueryDefinitionEntity> findAllByDatasourceId(UUID datasourceId);

    long countByFixedCredentialId(UUID fixedCredentialId);

    /** Enabled queries without schema snapshot; they are missing from the service document. */
    long countByEnabledTrueAndColumnsIsEmpty();
}
