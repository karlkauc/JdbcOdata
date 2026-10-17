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
        @DefaultValue Security security,
        @DefaultValue("") String masterKey,
        @DefaultValue("./data") String dataDir) {

    public record Admin(@DefaultValue("admin") String user, @DefaultValue("") String password) {
    }

    /**
     * {@code anonymousUser}: name of a service user whose identity is used for requests without an
     * {@code Authorization} header (public demo feeds, OData explorers without a login dialog). Empty = every
     * request needs Basic credentials. The user must be enabled, must not be flagged force-passthrough and needs
     * stored credentials or FIXED queries; requests with a header are authenticated as before.
     * {@code pageSize}: server-driven paging, rows per response before an {@code @odata.nextLink}; 0 = off (clients
     * can still ask for pages with {@code Prefer: odata.maxpagesize}, never larger than this value when it is set).
     */
    public record OData(@DefaultValue("500000") int maxRows, @DefaultValue("60") int defaultTimeoutSec,
                        @DefaultValue("2000") long concurrencyWaitMs, @DefaultValue("") String anonymousUser,
                        @DefaultValue("0") int pageSize) {
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

    /**
     * {@code allowPlainHttp}: Basic Auth carries clear-text passwords, so without TLS the service refuses to start
     * unless this flag is set (e.g. behind a TLS-terminating reverse proxy or for local demos).
     * {@code rateLimitPerMinute}: failed logins per client IP and per username before requests are rejected with 429.
     */
    public record Security(@DefaultValue("false") boolean allowPlainHttp,
                           @DefaultValue("20") int rateLimitPerMinute) {
    }
}
