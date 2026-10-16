package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ODataServletTest {

    @Test
    void missingCredentialsYield401WithChallenge() throws Exception {
        ODataServlet servlet = new ODataServlet(mock(EdmRegistry.class), null, null, null, null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getHeader("WWW-Authenticate")).isEqualTo("Basic realm=\"JdbcOdata\"");
        assertThat(resp.getContentAsString()).contains("Authentication required");
    }

    @Test
    void failuresOutsideOlingoYield500Json() throws Exception {
        EdmRegistry registry = mock(EdmRegistry.class);
        when(registry.serviceMetadata()).thenThrow(new IllegalStateException("boom"));
        ODataServlet servlet = new ODataServlet(registry, null, null, null, null);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/odata/");
        req.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString("a:b".getBytes()));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        servlet.service(req, resp);
        assertThat(resp.getStatus()).isEqualTo(500);
        assertThat(resp.getContentAsString()).contains("Internal server error").doesNotContain("boom");
    }
}
