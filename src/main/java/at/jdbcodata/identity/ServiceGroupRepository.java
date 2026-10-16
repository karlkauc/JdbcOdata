package at.jdbcodata.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceGroupRepository extends JpaRepository<ServiceGroupEntity, UUID> {
    Optional<ServiceGroupEntity> findByNameIgnoreCase(String name);

    List<ServiceGroupEntity> findAllByOrderByName();
}
