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
        return new ProcessorSupport(null, null, null, null, null, null, new LoginRateLimiter(TestData.props(10, 10, "")),
                TestData.props(10, 10, ""), new DeltaTracker(null), new AsyncRequests());
    }

    private static at.jdbcodata.config.JdbcOdataProperties props() {
        return at.jdbcodata.testutil.TestData.props(1000, 60, "");
    }

    private static String basic(String user, String password) {
        return "Basic " + java.util.Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
    }

    @Test
    void missingCredentialsYield401WithChallenge() throws Exception {
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), mock(ExecutionIdentityResolver.class), support(), props());
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
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), resolver, support(), props());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        req.addHeader("Authorization", basic("svc_x", "bad"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("credentials were rejected").doesNotContain("svc_x");
    }

    @Test
    void oversizedBodiesAreRejected() throws Exception {
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), mock(ExecutionIdentityResolver.class), support(), props());
        MockHttpServletRequest declared = new MockHttpServletRequest("POST", "/odata/$batch") {
            @Override
            public long getContentLengthLong() {
                return BoundedRequest.MAX_BODY_BYTES + 1;
            }
        };
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(declared, resp);
        assertThat(resp.getStatus()).isEqualTo(413);

        MockHttpServletRequest chunked = new MockHttpServletRequest("POST", "/odata/$batch");
        chunked.setContent(new byte[(int) BoundedRequest.MAX_BODY_BYTES + 1]);
        var in = new BoundedRequest(chunked).getInputStream();
        org.assertj.core.api.Assertions.assertThatThrownBy(in::readAllBytes).isInstanceOf(java.io.IOException.class);
    }

    @Test
    void errorJsonIsEscaped() throws Exception {
        String json = JsonErrorProcessor.errorJson(400, "a\"b\\c\nd\u0001");
        assertThat(json).isEqualTo("{\"error\":{\"code\":\"400\",\"message\":\"a\\\"b\\\\c\\nd\\u0001\"}}");
        assertThat(tools.jackson.databind.json.JsonMapper.builder().build().readTree(json).get("error").get("message").asString())
                .isEqualTo("a\"b\\c\nd\u0001");
    }

    @Test
    void failuresOutsideOlingoYield500Json() throws Exception {
        ExecutionIdentityResolver resolver = mock(ExecutionIdentityResolver.class);
        when(resolver.authenticate(any())).thenThrow(new IllegalStateException("boom"));
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), resolver, support(), props());
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        req.addHeader("Authorization", basic("a", "b"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(resp.getContentAsString()).contains("Internal server error").doesNotContain("boom");
    }

    @Test
    void olingoRequestStripsServletPathButKeepsFullUrl() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/Kunden");
        req.setServletPath("/odata");
        req.setScheme("https");
        req.setServerName("odata.example.org");
        req.setServerPort(443);
        var wrapped = ODataServlet.olingoRequest(req);
        assertThat(wrapped.getServletPath()).isEmpty();
        assertThat(wrapped.getContextPath()).isEmpty();
        assertThat(wrapped.getRequestURI()).isEqualTo("/Kunden");
        assertThat(wrapped.getRequestURL().toString()).isEqualTo("https://odata.example.org/odata/Kunden");
        assertThat(wrapped.getHeader("Host")).isEqualTo(req.getHeader("Host"));
    }

    @Test
    void olingoRequestLeavesUriWithoutMappingPrefixUntouched() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/other");
        req.setServletPath("/odata");
        assertThat(ODataServlet.olingoRequest(req).getRequestURI()).isEqualTo("/other");
    }
}
