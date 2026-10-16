package at.jdbcodata.cache;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CacheEntryRepository extends JpaRepository<CacheEntryEntity, UUID> {
    Optional<CacheEntryEntity> findByQueryIdAndQueryVersionAndDbUserAndParamsHash(UUID queryId, int version, String dbUser, String paramsHash);

    List<CacheEntryEntity> findAllByOrderByLastAccessAtDesc(Pageable pageable);

    List<CacheEntryEntity> findAllByQueryIdOrderByLastAccessAtDesc(UUID queryId);

    @Modifying
    @Query("delete from CacheEntryEntity e where e.queryId = :queryId")
    int deleteByQuery(UUID queryId);

    @Modifying
    @Query("delete from CacheEntryEntity e where e.queryId = :queryId and e.dbUser = :dbUser")
    int deleteByQueryAndUser(UUID queryId, String dbUser);

    @Modifying
    @Query("delete from CacheEntryEntity e where e.expiresAt < :now")
    int deleteExpired(Instant now);

    @Query("select coalesce(sum(e.byteSize), 0) from CacheEntryEntity e")
    long totalBytes();

    @Query("select e.id from CacheEntryEntity e order by e.lastAccessAt asc")
    List<UUID> idsByLastAccess(Pageable pageable);

    long countByQueryId(UUID queryId);
}
