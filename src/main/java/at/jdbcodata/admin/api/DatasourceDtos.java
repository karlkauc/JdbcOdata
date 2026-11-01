package at.jdbcodata.admin.api;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceSpec;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

final class DatasourceDtos {

    private DatasourceDtos() {
    }

    record DatasourceRequest(
            @NotBlank @Size(max = 100) @Pattern(regexp = "[A-Za-z0-9_-]+") String name,
            @Size(max = 1000) String description,
            @NotBlank @Size(max = 2000) String jdbcUrl,
            @Size(max = 300) String driverClass,
            @Size(max = 100) String timezone,
            @Min(1) @Max(3600) Integer defaultTimeoutSec,
            @Min(1) Integer maxRows,
            @Min(1) @Max(1000) Integer maxConcurrent,
            Boolean enabled,
            Map<String, String> sessionSettings,
            Map<String, String> driverProperties,
            @Min(1) @Max(100) Integer poolSize) {

        /** Missing maps keep the stored session settings and driver properties, empty maps remove them. */
        DatasourceSpec toSpec() {
            return new DatasourceSpec(name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows,
                    maxConcurrent, enabled, sessionSettings, driverProperties, poolSize);
        }
    }

    record DatasourceResponse(
            UUID id,
            String name,
            String description,
            String jdbcUrl,
            String driverClass,
            String timezone,
            int defaultTimeoutSec,
            int maxRows,
            int maxConcurrent,
            boolean enabled,
            Instant createdAt,
            Instant updatedAt,
            Map<String, String> sessionSettings,
            Map<String, String> driverProperties,
            int poolSize) {

        static DatasourceResponse from(Datasource d) {
            return new DatasourceResponse(d.id(), d.name(), d.description(), d.jdbcUrl(), d.driverClass(),
                    d.timezone(), d.defaultTimeoutSec(), d.maxRows(), d.maxConcurrent(), d.enabled(),
                    d.createdAt(), d.updatedAt(), d.sessionSettings(), d.driverProperties(), d.poolSize());
        }
    }

    record ConnectionTestRequest(@NotBlank String username, @NotNull String password) {
    }
}
