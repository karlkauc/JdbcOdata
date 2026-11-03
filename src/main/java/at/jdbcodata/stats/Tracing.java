package at.jdbcodata.stats;

import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DbExecutionException;
import at.jdbcodata.query.QueryDefinition;
import io.micrometer.common.KeyValue;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.filter.ServerHttpObservationFilter;

/**
 * Spans for OpenTelemetry, off unless {@code management.tracing.export.enabled}. They carry only names from the
 * configuration and counts: no SQL, no parameter values, no user names and no error texts of the database, which
 * may quote values.
 */
@Component
public class Tracing {

    public static final Tracing OFF = new Tracing(OpenTelemetry.noop());

    static final AttributeKey<String> DB_SYSTEM = AttributeKey.stringKey("db.system.name");
    static final AttributeKey<Long> DB_ROWS = AttributeKey.longKey("db.response.returned_rows");
    static final AttributeKey<String> ERROR_TYPE = AttributeKey.stringKey("error.type");
    static final AttributeKey<String> DATASOURCE = AttributeKey.stringKey("jdbcodata.datasource");
    static final AttributeKey<String> QUERY = AttributeKey.stringKey("jdbcodata.query");

    private final Tracer tracer;
    private final boolean enabled;

    @Autowired
    public Tracing(ObjectProvider<OpenTelemetry> openTelemetry,
                   @Value("${management.tracing.export.enabled:true}") boolean enabled) {
        this(enabled ? openTelemetry.getIfAvailable(OpenTelemetry::noop) : OpenTelemetry.noop());
    }

    private Tracing(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("at.jdbcodata");
        this.enabled = openTelemetry != OpenTelemetry.noop();
    }

    /**
     * A span for one statement of {@code query}, as child of the current span; {@link DbSpan#end} or
     * {@link DbSpan#fail} closes it.
     */
    public DbSpan db(String operation, QueryDefinition query, Datasource ds) {
        Span span = tracer.spanBuilder(operation + " " + query.name()).setSpanKind(SpanKind.CLIENT)
                .setAttribute(DB_SYSTEM, system(ds.jdbcUrl()))
                .setAttribute(DATASOURCE, ds.name())
                .setAttribute(QUERY, query.name())
                .startSpan();
        return new DbSpan(span);
    }

    /** The driver name of the URL ({@code postgresql}, {@code oracle}, ...), without host or database. */
    static String system(String jdbcUrl) {
        String[] parts = jdbcUrl == null ? new String[0] : jdbcUrl.split(":", 3);
        return parts.length > 2 && parts[0].equalsIgnoreCase("jdbc") ? parts[1].toLowerCase(Locale.ROOT) : "other";
    }

    /**
     * Puts the outcome of an execution on the span of the HTTP request that caused it, so that a trace can be found
     * by its query. Jobs without a request (asynchronous ones) have it on their database span.
     */
    void annotate(ExecutionRecord r) {
        if (!enabled || !(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request)) return;
        ServerHttpObservationFilter.findObservationContext(request.getRequest()).ifPresent(context -> {
            context.addHighCardinalityKeyValue(KeyValue.of(QUERY.getKey(), r.queryName()));
            if (r.datasourceName() != null) context.addHighCardinalityKeyValue(KeyValue.of(DATASOURCE.getKey(), r.datasourceName()));
            context.addHighCardinalityKeyValue(KeyValue.of("jdbcodata.status", r.status().name()));
            context.addHighCardinalityKeyValue(KeyValue.of("jdbcodata.cache", r.cacheStatus().name()));
            if (r.executionMode() != null) context.addHighCardinalityKeyValue(KeyValue.of("jdbcodata.mode", r.executionMode().name()));
            if (r.rowCount() != null) context.addHighCardinalityKeyValue(KeyValue.of("jdbcodata.rows", r.rowCount().toString()));
            if (r.bytesSent() != null) context.addHighCardinalityKeyValue(KeyValue.of("jdbcodata.bytes", r.bytesSent().toString()));
        });
    }

    /** A running database span. */
    public static final class DbSpan {

        private final Span span;

        private DbSpan(Span span) {
            this.span = span;
        }

        /** Marks a step, e.g. that the connection is there, so that waiting and running can be told apart. */
        public void event(String name) {
            span.addEvent(name);
        }

        public void end(long rows) {
            span.setAttribute(DB_ROWS, rows);
            span.end();
        }

        /** Ends with an error; only its kind is recorded, never its message. */
        public void fail(RuntimeException e) {
            span.setAttribute(ERROR_TYPE, e instanceof DbExecutionException db && db.isTimeout() ? "timeout" : e.getClass().getSimpleName());
            span.setStatus(StatusCode.ERROR);
            span.end();
        }
    }
}
