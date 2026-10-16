package at.jdbcodata.stats;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Micrometer counters and timers for Prometheus (FR-57). */
@Component
public class ExecutionMetrics {

    private final MeterRegistry registry;

    public ExecutionMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(ExecutionRecord r) {
        registry.counter("jdbcodata.executions", "query", r.queryName(), "status", r.status().name(),
                "cache", r.cacheStatus().name(), "mode", r.executionMode() == null ? "none" : r.executionMode().name()).increment();
        Timer.builder("jdbcodata.execution.duration").tag("query", r.queryName()).register(registry)
                .record(Duration.ofMillis(r.totalDurationMs()));
        if (r.rowCount() != null) {
            registry.summary("jdbcodata.execution.rows", "query", r.queryName()).record(r.rowCount());
        }
    }
}
