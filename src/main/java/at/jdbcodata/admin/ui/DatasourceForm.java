package at.jdbcodata.admin.ui;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceSpec;
import at.jdbcodata.datasource.SessionSettings;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class DatasourceForm {

    @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+", message = "only letters, digits, _ and -")
    private String name;
    @Size(max = 1000)
    private String description;
    @NotBlank @Size(max = 2000)
    private String jdbcUrl;
    @Size(max = 300)
    private String driverClass;
    @Size(max = 100)
    private String timezone;
    @Min(1) @Max(3600)
    private Integer defaultTimeoutSec = 60;
    @Min(1)
    private Integer maxRows = 500_000;
    @Min(1) @Max(1000)
    private Integer maxConcurrent = 10;
    private boolean enabled = true;
    @Size(max = 10_000)
    private String sessionSettings = "";
    @Size(max = 10_000)
    private String driverProperties = "";

    public static DatasourceForm from(Datasource d) {
        DatasourceForm f = new DatasourceForm();
        f.name = d.name();
        f.description = d.description();
        f.jdbcUrl = d.jdbcUrl();
        f.driverClass = d.driverClass();
        f.timezone = d.timezone();
        f.defaultTimeoutSec = d.defaultTimeoutSec();
        f.maxRows = d.maxRows();
        f.maxConcurrent = d.maxConcurrent();
        f.enabled = d.enabled();
        f.sessionSettings = SessionSettings.toLines(d.sessionSettings());
        f.driverProperties = SessionSettings.toLines(d.driverProperties());
        return f;
    }

    public DatasourceSpec toSpec() {
        return new DatasourceSpec(name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows,
                maxConcurrent, enabled, SessionSettings.fromLines(sessionSettings), SessionSettings.fromLines(driverProperties));
    }

    /** Session setting keys for the database of the current JDBC URL, for the hint below the field. */
    public String getAllowedSessionKeys() {
        return String.join(", ", SessionSettings.allowedKeys(jdbcUrl));
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getJdbcUrl() { return jdbcUrl; }
    public void setJdbcUrl(String jdbcUrl) { this.jdbcUrl = jdbcUrl; }
    public String getDriverClass() { return driverClass; }
    public void setDriverClass(String driverClass) { this.driverClass = driverClass; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public Integer getDefaultTimeoutSec() { return defaultTimeoutSec; }
    public void setDefaultTimeoutSec(Integer defaultTimeoutSec) { this.defaultTimeoutSec = defaultTimeoutSec; }
    public Integer getMaxRows() { return maxRows; }
    public void setMaxRows(Integer maxRows) { this.maxRows = maxRows; }
    public Integer getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(Integer maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSessionSettings() { return sessionSettings; }
    public void setSessionSettings(String sessionSettings) { this.sessionSettings = sessionSettings; }
    public String getDriverProperties() { return driverProperties; }
    public void setDriverProperties(String driverProperties) { this.driverProperties = driverProperties; }
}
