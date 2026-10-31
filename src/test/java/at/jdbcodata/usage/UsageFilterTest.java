package at.jdbcodata.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UsageFilterTest {

    private final List<UsageEvent> events = new ArrayList<>();
    private final UsageRecorder recorder = new UsageRecorder(new UsageProperties("", "", "", "")) {
        @Override
        public boolean record(UsageEvent event) {
            return events.add(event);
        }
    };
    private final PublishedQueryNames queries = new PublishedQueryNames(null) {
        @Override
        boolean contains(String name) {
            return name.equals("Umsatz");
        }
    };
    private final UsageFilter filter = new UsageFilter(recorder, new VisitorHasher("secret"), queries,
            Clock.fixed(Instant.parse("2026-10-31T10:00:00Z"), ZoneOffset.UTC), "1.1.0");

    private UsageEvent get(String path, String query) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        request.setQueryString(query);
        request.addHeader("User-Agent", "curl/8.18.0");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return events.getLast();
    }

    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {
            "/odata, service_doc, null",
            "/odata/, service_doc, null",
            "/odata/$metadata, metadata, null",
            "/odata/$batch, batch, null",
            "/odata/Umsatz, data_query, Umsatz",
            "/odata/Umsatz(1)/Kunde, data_query, Umsatz",
            "/odata/Umsatz/$count, data_query, Umsatz",
            "/odata/.env, data_query, other",
            "/odata/geheim'or'1, data_query, other"})
    void sortsRequests(String path, String eventType, String target) throws Exception {
        UsageEvent event = get(path, null);
        assertThat(event.eventType()).isEqualTo(eventType);
        assertThat(event.targetNamespace()).isEqualTo(target);
    }

    @Test
    void keepsOptionNamesOnly() throws Exception {
        UsageEvent event = get("/odata/Umsatz", "$top=5&%24filter=Kunde%20eq%20'Geheim'&p_jahr=2025&p_kunde=Huber");
        assertThat(event.queryOptions()).isEqualTo("$filter,$top,param");
        assertThat(event.toString()).doesNotContain("Geheim", "Huber", "2025", "p_kunde");
    }

    @Test
    void keepsNoUserNameAndNoIp() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/odata/Umsatz");
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("Authorization", "Basic YW5uYTpnZWhlaW0=");
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        UsageEvent event = events.getLast();
        assertThat(event.authUsed()).isTrue();
        assertThat(event.toString()).doesNotContain("203.0.113.7", "anna", "YW5uYTpnZWhlaW0");
    }

    @Test
    void controlCharactersLeaveTheUserAgent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/odata/");
        request.addHeader("User-Agent", "a\u0000b" + "x".repeat(300));
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertThat(events.getLast().userAgent()).hasSize(255).startsWith("abx");
    }

    @ParameterizedTest
    @CsvSource({"200, ok", "304, ok", "400, rejected", "401, denied", "403, denied", "404, rejected", "429, rate_limited",
            "500, error", "503, error"})
    void mapsStatus(int code, String status) {
        assertThat(UsageFilter.status(code)).isEqualTo(status);
    }

    @Test
    void aFailingRequestStaysFailingAndIsCounted() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/odata/Umsatz");
        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            throw new ServletException("boom");
        })).isInstanceOf(ServletException.class);
        assertThat(events.getLast().statusCode()).isEqualTo(500);
    }

    @Test
    void aBrokenRecorderNeverReachesTheClient() throws Exception {
        UsageFilter broken = new UsageFilter(new UsageRecorder(new UsageProperties("", "", "", "")) {
            @Override
            public boolean record(UsageEvent event) {
                throw new IllegalStateException("boom");
            }
        }, new VisitorHasher("secret"), queries, Clock.systemUTC(), "1.1.0");
        MockHttpServletResponse response = new MockHttpServletResponse();
        broken.doFilter(new MockHttpServletRequest("GET", "/odata/"), response, new MockFilterChain());
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
