package at.jdbcodata.odata;

import at.jdbcodata.audit.LoginAudit;
import at.jdbcodata.cache.ResultCache;
import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.query.QueryExecutor;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.security.LoginRateLimiter;
import at.jdbcodata.stats.ExecutionLogService;
import org.springframework.stereotype.Component;

/** Spring beans the per-request OData processor needs. */
@Component
public record ProcessorSupport(
        QueryService queryService,
        DatasourceService datasourceService,
        ExecutionIdentityResolver identityResolver,
        QueryExecutor executor,
        ResultCache cache,
        ExecutionLogService executionLog,
        LoginRateLimiter rateLimiter,
        JdbcOdataProperties properties,
        DeltaTracker deltaTracker,
        AsyncRequests asyncRequests,
        LoginAudit loginAudit) {
}
