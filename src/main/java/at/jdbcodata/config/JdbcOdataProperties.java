package at.jdbcodata.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "jdbcodata")
public record JdbcOdataProperties(
        @DefaultValue Admin admin,
        @DefaultValue OData odata,
        @DefaultValue Seed seed) {

    public record Admin(@DefaultValue("admin") String user, @DefaultValue("") String password) {
    }

    public record OData(@DefaultValue("500000") int maxRows, @DefaultValue("60") int defaultTimeoutSec) {
    }

    public record Seed(@DefaultValue("") String file) {
    }
}
