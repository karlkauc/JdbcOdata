package at.jdbcodata.common;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@ExtendWith(OutputCaptureExtension.class)
class RequestIdFilterTest extends DemoProfileTest {

    @Test
    void generatesAnIdForEveryResponse() throws Exception {
        for (String path : new String[] {"/odata/", "/api/v1/audit", "/actuator/health", "/login"}) {
            var response = get(path, null, null);
            assertThat(response.headers().firstValue(RequestIdFilter.HEADER)).as(path).isPresent();
        }
    }

    @Test
    void keepsTheIdOfAProxyButNotAnUnsafeOne() throws Exception {
        var kept = send("GET", "/odata/", null, null, null, RequestIdFilter.HEADER, "proxy-1.a_b");
        assertThat(kept.headers().firstValue(RequestIdFilter.HEADER)).contains("proxy-1.a_b");

        var replaced = send("GET", "/odata/", null, null, null, RequestIdFilter.HEADER, "bad id {}");
        assertThat(replaced.headers().firstValue(RequestIdFilter.HEADER).orElseThrow()).hasSize(36);
        var tooLong = send("GET", "/odata/", null, null, null, RequestIdFilter.HEADER, "x".repeat(65));
        assertThat(tooLong.headers().firstValue(RequestIdFilter.HEADER).orElseThrow()).hasSize(36);
    }

    @Test
    void idIsAvailableDuringTheRequestAndRemovedAfterIt() throws Exception {
        String[] seen = new String[1];
        var chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
                seen[0] = MDC.get(RequestIdFilter.MDC_KEY);
            }
        };
        var response = new MockHttpServletResponse();
        new RequestIdFilter().doFilter(new MockHttpServletRequest("GET", "/odata/"), response, chain);

        assertThat(seen[0]).isNotBlank().isEqualTo(response.getHeader(RequestIdFilter.HEADER));
        assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
    }

    @Test
    void logLinesCarryTheId(CapturedOutput output) throws Exception {
        try (var ignored = MDC.putCloseable(RequestIdFilter.MDC_KEY, "log-line-test")) {
            LoggerFactory.getLogger(RequestIdFilterTest.class).info("inside of a request");
        }
        assertThat(output.getOut()).containsPattern("\\[log-line-test\\].*inside of a request");
    }
}
