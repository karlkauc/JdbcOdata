package at.jdbcodata.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Technical database account a FIXED-mode query runs under. */
@Entity
@Table(name = "fixed_credential")
public class FixedCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "db_username", nullable = false, length = 200)
    private String dbUsername;

    @Column(name = "db_password_enc", nullable = false, length = 4000)
    private String dbPasswordEnc;

    @Column(name = "key_id", nullable = false, length = 16)
    private String keyId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public FixedCredential toRecord(String datasourceName) {
        return new FixedCredential(id, name, description, datasourceId, datasourceName, dbUsername, createdAt, updatedAt);
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getDatasourceId() { return datasourceId; }
    public void setDatasourceId(UUID datasourceId) { this.datasourceId = datasourceId; }
    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }
    public String getDbPasswordEnc() { return dbPasswordEnc; }
    public void setDbPasswordEnc(String dbPasswordEnc) { this.dbPasswordEnc = dbPasswordEnc; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
