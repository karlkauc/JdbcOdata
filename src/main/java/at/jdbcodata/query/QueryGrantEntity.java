package at.jdbcodata.query;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "query_grant")
public class QueryGrantEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "query_id", nullable = false)
    private UUID queryId;

    @Enumerated(EnumType.STRING)
    @Column(name = "principal_type", nullable = false, length = 20)
    private PrincipalType principalType;

    @Column(name = "principal_id")
    private UUID principalId;

    @Column(name = "granted_by", length = 100)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    static QueryGrantEntity of(UUID queryId, PrincipalType type, UUID principalId, String grantedBy) {
        QueryGrantEntity e = new QueryGrantEntity();
        e.queryId = queryId;
        e.principalType = type;
        e.principalId = principalId;
        e.grantedBy = grantedBy;
        e.grantedAt = Instant.now();
        return e;
    }

    public UUID getId() { return id; }
    public UUID getQueryId() { return queryId; }
    public PrincipalType getPrincipalType() { return principalType; }
    public UUID getPrincipalId() { return principalId; }
    public String getGrantedBy() { return grantedBy; }
    public Instant getGrantedAt() { return grantedAt; }
}
