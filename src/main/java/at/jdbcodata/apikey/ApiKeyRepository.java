package at.jdbcodata.apikey;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApiKeyRepository extends JpaRepository<ApiKeyEntity, UUID> {

    List<ApiKeyEntity> findAllByOrderByCreatedAtDesc();

    Optional<ApiKeyEntity> findByKeyHash(String keyHash);

    boolean existsByNameIgnoreCase(String name);

    /** Writes only if the stored time is older than {@code before}, so a busy key is not a write per request. */
    @Modifying
    @Query("update ApiKeyEntity k set k.lastUsedAt = :now where k.id = :id and (k.lastUsedAt is null or k.lastUsedAt < :before)")
    void touch(@Param("id") UUID id, @Param("now") Instant now, @Param("before") Instant before);
}
