package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import at.jdbcodata.identity.ExecutionIdentityResolver;
import at.jdbcodata.identity.ServiceAuthenticationException;
import at.jdbcodata.security.LoginRateLimiter;
import at.jdbcodata.testutil.TestData;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ODataServletTest {

    private static ProcessorSupport support() {
        return new ProcessorSupport(null, null, null, null, null, null, new LoginRateLimiter(TestData.props(10, 10, "")));
    }

    private static String basic(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    @Test
    void missingCredentialsYield401WithChallenge() throws Exception {
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), mock(ExecutionIdentityResolver.class), support());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"JdbcOdata\"");
        assertThat(resp.getContentAsString()).contains("Authentication required");
    }

    @Test
    void wrongServicePasswordYields401() throws Exception {
        ExecutionIdentityResolver resolver = mock(ExecutionIdentityResolver.class);
        when(resolver.authenticate(any())).thenThrow(new ServiceAuthenticationException("svc_x"));
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), resolver, support());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        req.addHeader("Authorization", basic("svc_x", "bad"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("svc_x");
    }

    @Test
    void failuresOutsideOlingoYield500Json() throws Exception {
        ExecutionIdentityResolver resolver = mock(ExecutionIdentityResolver.class);
        when(resolver.authenticate(any())).thenThrow(new IllegalStateException("boom"));
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), resolver, support());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        req.addHeader("Authorization", basic("a", "b"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(resp.getContentAsString()).contains("Internal server error").doesNotContain("boom");
    }

    @Test
    void readsForwardedClientIp() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        assertThat(ODataServlet.clientIp(req)).isEqualTo("10.0.0.1");
        req.addHeader("X-Forwarded-For", "192.168.1.5, 10.0.0.2");
        assertThat(ODataServlet.clientIp(req)).isEqualTo("192.168.1.5");
    }
}
