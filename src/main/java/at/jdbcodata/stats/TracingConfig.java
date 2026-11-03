package at.jdbcodata.stats;

import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.exporter.SpanExportingPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** Leaves out what only costs volume once tracing is on. Without tracing, metrics stay as they are. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty("management.tracing.export.enabled")
class TracingConfig {

    /**
     * Background jobs start a trace every few seconds and never run a query; the passes through the security filters
     * take time that the span of the request already shows.
     */
    @Bean
    SpanExportingPredicate quietSpans() {
        return span -> span.getName() == null
                || !span.getName().startsWith("task ") && !span.getName().startsWith("security filterchain ");
    }

    /**
     * Health probes and Prometheus scrapes would be most traces. Their HTTP metrics go with them, which only matters
     * with tracing on.
     */
    @Bean
    ObservationPredicate noActuatorRequests(@Value("${management.endpoints.web.base-path:/actuator}") String basePath) {
        return (name, context) -> {
            HttpServletRequest request = context instanceof ServerRequestObservationContext server ? server.getCarrier()
                    : RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes current ? current.getRequest() : null;
            return request == null || !request.getRequestURI().startsWith(request.getContextPath() + basePath);
        };
    }
}
