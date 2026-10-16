package at.jdbcodata.datasource;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "datasource")
public class DatasourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(name = "jdbc_url", nullable = false, length = 2000)
    private String jdbcUrl;

    @Column(name = "driver_class", length = 300)
    private String driverClass;

    @Column(length = 100)
    private String timezone;

    @Column(name = "default_timeout_sec", nullable = false)
    private int defaultTimeoutSec = 60;

    @Column(name = "max_rows", nullable = false)
    private int maxRows = 500_000;

    @Column(name = "max_concurrent", nullable = false)
    private int maxConcurrent = 10;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Datasource toRecord() {
        return new Datasource(id, name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows,
                maxConcurrent, enabled, createdAt, updatedAt);
    }

    public UUID getId() { return id; }
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
    public int getDefaultTimeoutSec() { return defaultTimeoutSec; }
    public void setDefaultTimeoutSec(int defaultTimeoutSec) { this.defaultTimeoutSec = defaultTimeoutSec; }
    public int getMaxRows() { return maxRows; }
    public void setMaxRows(int maxRows) { this.maxRows = maxRows; }
    public int getMaxConcurrent() { return maxConcurrent; }
    public void setMaxConcurrent(int maxConcurrent) { this.maxConcurrent = maxConcurrent; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
