package at.jdbcodata.stats;

import at.jdbcodata.datasource.ConcurrencyLimiter;
import at.jdbcodata.datasource.IdentityPoolManager;
import at.jdbcodata.datasource.ResponseBudget;
import at.jdbcodata.security.LoginRateLimiter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

/**
 * Load of the identity pools and limiters. Totals only: a tag per pool would put database logins into the metrics
 * and grow with every user.
 */
@Component
public class RuntimeMetrics implements MeterBinder {

    private final IdentityPoolManager pools;
    private final ConcurrencyLimiter limiter;
    private final LoginRateLimiter rateLimiter;
    private final ExecutionLogService executionLog;
    private final ResponseBudget responseBudget;

    public RuntimeMetrics(IdentityPoolManager pools, ConcurrencyLimiter limiter, LoginRateLimiter rateLimiter,
                          ExecutionLogService executionLog, ResponseBudget responseBudget) {
        this.pools = pools;
        this.limiter = limiter;
        this.rateLimiter = rateLimiter;
        this.executionLog = executionLog;
        this.responseBudget = responseBudget;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("jdbcodata.pools", pools, IdentityPoolManager::activePoolCount)
                .description("Open connection pools, one per database login").register(registry);
        Gauge.builder("jdbcodata.pool.connections.active", pools, IdentityPoolManager::activeConnections)
                .description("Connections in use across all pools").register(registry);
        Gauge.builder("jdbcodata.pool.connections.pending", pools, IdentityPoolManager::pendingConnections)
                .description("Requests waiting for a connection; steadily above 0 means poolSize is too small")
                .register(registry);
        Gauge.builder("jdbcodata.executions.running", limiter, ConcurrencyLimiter::usedPermits)
                .description("Query executions holding a concurrency slot").register(registry);
        Gauge.builder("jdbcodata.response.rows.reserved", responseBudget, ResponseBudget::reservedRows)
                .description("Rows of responses being built or written").register(registry);
        Gauge.builder("jdbcodata.response.rows.budget", responseBudget, ResponseBudget::rows)
                .description("Rows allowed in responses at the same time").register(registry);
        FunctionCounter.builder("jdbcodata.ratelimit.rejected", rateLimiter, LoginRateLimiter::rejected)
                .description("Requests answered with 429 after too many failed logins").register(registry);
        FunctionCounter.builder("jdbcodata.executionlog.dropped", executionLog, ExecutionLogService::droppedRecords)
                .description("Execution log records that could not be written").register(registry);
    }
}
