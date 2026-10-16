package at.jdbcodata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "jdbcodata")
public record JdbcOdataProperties(
        @DefaultValue Admin admin,
        @DefaultValue OData odata,
        @DefaultValue Seed seed,
        @DefaultValue Cache cache,
        @DefaultValue Stats stats,
        @DefaultValue("") String masterKey,
        @DefaultValue("./data") String dataDir) {

    public record Admin(@DefaultValue("admin") String user, @DefaultValue("") String password) {
    }

    public record OData(@DefaultValue("500000") int maxRows, @DefaultValue("60") int defaultTimeoutSec) {
    }

    public record Seed(@DefaultValue("") String file) {
    }

    /** Persistent cache limit, in-memory layer limit and cleanup interval. */
    public record Cache(@DefaultValue("1024") int maxSizeMb, @DefaultValue("256") int memoryMb,
                        @DefaultValue("300000") long cleanupIntervalMs) {
    }

    /** Execution log retention and whether records are written asynchronously. */
    public record Stats(@DefaultValue("90") int retentionDays, @DefaultValue("true") boolean async) {
    }
}
