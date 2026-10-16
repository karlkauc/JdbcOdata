package at.jdbcodata.identity;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ServiceUserRepository extends JpaRepository<ServiceUserEntity, UUID> {
    Optional<ServiceUserEntity> findByUsernameIgnoreCase(String username);

    List<ServiceUserEntity> findAllByOrderByUsername();
}
