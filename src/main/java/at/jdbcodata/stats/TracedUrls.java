package at.jdbcodata.stats;

import at.jdbcodata.odata.ODataConfig;
import io.micrometer.common.KeyValue;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerHttpObservationDocumentation;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.stereotype.Component;

/**
 * The URL in traces and metrics of a request. An OData path names keys and parameter values, i.e. data, so only the
 * entity set stays, e.g. {@code /odata/Customers}. Other paths keep their form; the query string is never part of it.
 */
@Component
public class TracedUrls extends DefaultServerRequestObservationConvention {

    /**
     * {@code http get /odata/Customers} for the read of a feed, so that traces can be told apart by name. Only for a
     * feed that ran: a name per path that anyone can send would flood the list of span names.
     */
    @Override
    public String getContextualName(ServerRequestObservationContext context) {
        String name = super.getContextualName(context);
        HttpServletRequest request = context.getCarrier();
        if (request == null || context.getHighCardinalityKeyValue(Tracing.QUERY.getKey()) == null) return name;
        String url = shorten(request.getRequestURI(), request.getContextPath());
        return url.startsWith(request.getContextPath() + ODataConfig.BASE_PATH) ? name + " " + url : name;
    }

    @Override
    protected KeyValue httpUrl(ServerRequestObservationContext context) {
        if (context.getCarrier() == null) return super.httpUrl(context);
        return KeyValue.of(ServerHttpObservationDocumentation.HighCardinalityKeyNames.HTTP_URL,
                shorten(context.getCarrier().getRequestURI(), context.getCarrier().getContextPath()));
    }

    static String shorten(String uri, String contextPath) {
        if (uri == null) return "UNKNOWN";
        String base = (contextPath == null ? "" : contextPath) + ODataConfig.BASE_PATH;
        if (!uri.equals(base) && !uri.startsWith(base + "/")) return uri;
        String rest = uri.substring(base.length());
        int end = 1;
        while (end < rest.length() && "/(%;".indexOf(rest.charAt(end)) < 0) end++;
        return base + rest.substring(0, Math.min(end, rest.length()));
    }
}
