package at.jdbcodata.admin.ui;

import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public class FixedCredentialForm {

    @NotBlank @Size(max = 100)
    private String name;
    @Size(max = 1000)
    private String description;
    @NotNull
    private UUID datasourceId;
    @NotBlank @Size(max = 200)
    private String dbUsername;
    private String dbPassword;

    public static FixedCredentialForm from(FixedCredential c) {
        FixedCredentialForm f = new FixedCredentialForm();
        f.name = c.name();
        f.description = c.description();
        f.datasourceId = c.datasourceId();
        f.dbUsername = c.dbUsername();
        return f;
    }

    public FixedCredentialService.FixedCredentialSpec toSpec() {
        return new FixedCredentialService.FixedCredentialSpec(name, description, datasourceId, dbUsername, dbPassword);
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public UUID getDatasourceId() { return datasourceId; }
    public void setDatasourceId(UUID datasourceId) { this.datasourceId = datasourceId; }
    public String getDbUsername() { return dbUsername; }
    public void setDbUsername(String dbUsername) { this.dbUsername = dbUsername; }
    public String getDbPassword() { return dbPassword; }
    public void setDbPassword(String dbPassword) { this.dbPassword = dbPassword; }
}
