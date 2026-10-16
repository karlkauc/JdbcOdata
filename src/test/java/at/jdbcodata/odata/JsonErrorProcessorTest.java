package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ODataServerError;
import org.junit.jupiter.api.Test;

class JsonErrorProcessorTest {

    private final JsonErrorProcessor processor = new JsonErrorProcessor();

    JsonErrorProcessorTest() {
        processor.init(OData.newInstance(), null);
    }

    @Test
    void addsBasicChallengeOn401() throws Exception {
        ODataServerError error = new ODataServerError().setStatusCode(401).setMessage("nope")
                .setException(new ODataApplicationException("nope", 401, Locale.ENGLISH));
        ODataResponse response = new ODataResponse();
        processor.processError(request(), response, error, ContentType.JSON);
        assertThat(response.getStatusCode()).isEqualTo(401);
        assertThat(String.valueOf(response.getHeader("WWW-Authenticate"))).contains("Basic realm=\"JdbcOdata\"");
        assertThat(body(response)).contains("\"message\":\"nope\"").contains("\"code\":\"401\"");
    }

    @Test
    void hidesUnexpectedExceptionMessages() throws Exception {
        ODataServerError error = new ODataServerError().setStatusCode(500).setMessage("SELECT secret FROM t failed")
                .setException(new IllegalStateException("SELECT secret FROM t failed"));
        ODataResponse response = new ODataResponse();
        processor.processError(request(), response, error, null);
        assertThat(response.getStatusCode()).isEqualTo(500);
        assertThat(body(response)).contains("Internal server error").doesNotContain("secret");
        assertThat(String.valueOf(response.getHeader("Content-Type"))).contains("application/json");
    }

    @Test
    void keepsApplicationErrorsAbove500Visible() throws Exception {
        ODataServerError error = new ODataServerError().setStatusCode(503).setMessage("no snapshot").setCode("x")
                .setException(new ODataApplicationException("no snapshot", 503, Locale.ENGLISH));
        ODataResponse response = new ODataResponse();
        processor.processError(request(), response, error, ContentType.JSON);
        assertThat(body(response)).contains("no snapshot").contains("\"code\":\"x\"");
    }

    private static ODataRequest request() {
        ODataRequest r = new ODataRequest();
        r.setRawRequestUri("http://localhost/odata/Sales");
        return r;
    }

    private static String body(ODataResponse response) throws Exception {
        return new String(response.getContent().readAllBytes(), StandardCharsets.UTF_8);
    }
}
