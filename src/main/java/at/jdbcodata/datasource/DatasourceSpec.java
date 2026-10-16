package at.jdbcodata.datasource;

/** Input for creating or updating a datasource. Null numeric fields keep the current value or the default. */
public record DatasourceSpec(
        String name,
        String description,
        String jdbcUrl,
        String driverClass,
        String timezone,
        Integer defaultTimeoutSec,
        Integer maxRows,
        Integer maxConcurrent,
        Boolean enabled) {
}
