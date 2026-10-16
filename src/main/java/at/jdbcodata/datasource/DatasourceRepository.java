package at.jdbcodata.datasource;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DatasourceRepository extends JpaRepository<DatasourceEntity, UUID> {
    Optional<DatasourceEntity> findByName(String name);
}
