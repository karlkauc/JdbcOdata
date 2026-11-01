package at.jdbcodata.datasource;

import java.util.Map;

/**
 * Input for creating or updating a datasource. Null numeric fields keep the current value or the default, null maps
 * keep the current session settings / driver properties.
 */
public record DatasourceSpec(
        String name,
        String description,
        String jdbcUrl,
        String driverClass,
        String timezone,
        Integer defaultTimeoutSec,
        Integer maxRows,
        Integer maxConcurrent,
        Boolean enabled,
        Map<String, String> sessionSettings,
        Map<String, String> driverProperties,
        Integer poolSize) {

    /** Without session settings and driver properties: keeps the current ones on update, none on create. */
    public DatasourceSpec(String name, String description, String jdbcUrl, String driverClass, String timezone,
                          Integer defaultTimeoutSec, Integer maxRows, Integer maxConcurrent, Boolean enabled) {
        this(name, description, jdbcUrl, driverClass, timezone, defaultTimeoutSec, maxRows, maxConcurrent, enabled,
                null, null, null);
    }
}
