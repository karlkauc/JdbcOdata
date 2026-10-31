package at.jdbcodata.usage;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UrlPathHelper;

/**
 * Records one {@link UsageEvent} per OData request. Runs before the rate limit and Spring Security, so refused
 * requests are counted too. Keeps only option names and published query names, so nothing from a URL reaches the
 * statistics as free text.
 */
public class UsageFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(UsageFilter.class);
    private static final UrlPathHelper PATHS = new UrlPathHelper();
    private static final String BASE_PATH = "/odata";
    private static final int MAX_USER_AGENT = 255;
    private static final Set<String> OPTIONS = Set.of("$apply", "$compute", "$count", "$deltatoken", "$expand", "$filter",
            "$format", "$id", "$levels", "$orderby", "$search", "$select", "$skip", "$skiptoken", "$top");

    private final UsageRecorder recorder;
    private final VisitorHasher hasher;
    private final PublishedQueryNames queries;
    private final Clock clock;
    private final String appVersion;

    UsageFilter(UsageRecorder recorder, VisitorHasher hasher, PublishedQueryNames queries, Clock clock, String appVersion) {
        this.recorder = recorder;
        this.hasher = hasher;
        this.queries = queries;
        this.clock = clock;
        this.appVersion = appVersion;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long start = System.nanoTime();
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = false;
        } finally {
            record(request, failed ? 500 : response.getStatus(), start);
        }
    }

    private void record(HttpServletRequest request, int statusCode, long start) {
        try {
            String path = PATHS.getPathWithinApplication(request);
            String segment = firstSegment(path);
            String userAgent = request.getHeader("User-Agent");
            String cleanAgent = userAgent == null ? null : clean(userAgent);
            recorder.record(new UsageEvent(eventType(segment), visitorHash(request, cleanAgent), cleanAgent,
                    UsageClassifier.device(cleanAgent), statusCode, appVersion, UsageClassifier.client(cleanAgent),
                    target(segment), (int) Math.min((System.nanoTime() - start) / 1_000_000, Integer.MAX_VALUE),
                    status(statusCode), queryOptions(request.getQueryString()),
                    request.getHeader("Authorization") != null));
        } catch (RuntimeException e) {
            log.warn("Usage event not recorded: {}", e.toString());
        }
    }

    private String visitorHash(HttpServletRequest request, String userAgent) {
        return hasher.hash(request.getRemoteAddr(), Objects.toString(userAgent, ""),
                LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    /** {@code /odata/Umsatz(1)/Kunde} gives {@code Umsatz}; the service document gives an empty string. */
    private static String firstSegment(String path) {
        String rest = path.length() > BASE_PATH.length() ? path.substring(BASE_PATH.length() + 1) : "";
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            if (rest.charAt(i) == '/' || rest.charAt(i) == '(') {
                end = i;
                break;
            }
        }
        return rest.substring(0, end);
    }

    private static String eventType(String segment) {
        return switch (segment) {
            case "" -> "service_doc";
            case "$metadata" -> "metadata";
            case "$batch" -> "batch";
            default -> "data_query";
        };
    }

    private String target(String segment) {
        if (segment.isEmpty() || segment.startsWith("$")) return null;
        return queries.contains(segment) ? segment : "other";
    }

    static String status(int statusCode) {
        if (statusCode < 400) return "ok";
        if (statusCode == 401 || statusCode == 403) return "denied";
        if (statusCode == 429) return "rate_limited";
        return statusCode < 500 ? "rejected" : "error";
    }

    /** Sorted names of the system query options; query parameters are admin-defined, so they only count as {@code param}. */
    static String queryOptions(String queryString) {
        if (queryString == null || queryString.isEmpty()) return null;
        return Arrays.stream(queryString.split("&"))
                .map(UsageFilter::optionName)
                .filter(name -> !name.isEmpty())
                .map(name -> OPTIONS.contains(name) ? name : "param")
                .distinct().sorted().collect(Collectors.joining(","));
    }

    private static String optionName(String pair) {
        String name = pair.substring(0, pair.contains("=") ? pair.indexOf('=') : pair.length());
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static String clean(String userAgent) {
        String text = userAgent.chars().filter(c -> !Character.isISOControl(c))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        return text.length() > MAX_USER_AGENT ? text.substring(0, MAX_USER_AGENT) : text;
    }
}
