package at.jdbcodata.identity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** DB credentials of a service user for one datasource; the password is encrypted by the vault. */
@Entity
@Table(name = "stored_credential")
public class StoredCredentialEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "service_user_id", nullable = false)
    private UUID serviceUserId;

    @Column(name = "datasource_id", nullable = false)
    private UUID datasourceId;

    @Column(name = "db_username", nullable = false, length = 200)
    private String dbUsername;

    @Column(name = "db_password_enc", nullable = false, length = 4000)
    private String dbPasswordEnc;

    @Column(name = "key_id", nullable = false, length = 16)
    private String keyId;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UUID getId() { return id; }
    public UUID getServiceUserId() { return serviceUserId; }
    public void setServiceUserId(UUID serviceUserId) { this.serviceUserId = serviceUserId; }
    public UUID getDatasourceId() { return datasourceId; }
    public void setDatasourceId(UUID datasourceId) { this.datasourceId = datasourceId; }
    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }
    public String getDbPasswordEnc() { return dbPasswordEnc; }
    public void setDbPasswordEnc(String dbPasswordEnc) { this.dbPasswordEnc = dbPasswordEnc; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String keyId) { this.keyId = keyId; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
