package at.jdbcodata.admin.account;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AdminUserRepository extends JpaRepository<AdminUserEntity, UUID> {
    Optional<AdminUserEntity> findByUsernameIgnoreCase(String username);

    List<AdminUserEntity> findAllByOrderByUsername();

    long countByRoleAndEnabledTrue(AdminRole role);
}
