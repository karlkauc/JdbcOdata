package at.jdbcodata.odata;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.http.HttpHeader;
import org.apache.olingo.commons.api.http.HttpStatusCode;
import org.apache.olingo.server.api.OData;
import org.apache.olingo.server.api.ODataApplicationException;
import org.apache.olingo.server.api.ODataLibraryException;
import org.apache.olingo.server.api.ODataRequest;
import org.apache.olingo.server.api.ODataResponse;
import org.apache.olingo.server.api.ODataServerError;
import org.apache.olingo.server.api.ServiceMetadata;
import org.apache.olingo.server.api.processor.ErrorProcessor;
import org.apache.olingo.server.api.serializer.SerializerException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serialises errors in OData JSON format, adds the Basic challenge on 401 and makes sure no unexpected
 * exception text (which might contain SQL) reaches the client.
 */
public class JsonErrorProcessor implements ErrorProcessor {

    private static final Logger log = LoggerFactory.getLogger(JsonErrorProcessor.class);
    static final String BASIC_CHALLENGE = "Basic realm=\"JdbcOdata\"";

    private OData odata;

    @Override
    public void init(OData odata, ServiceMetadata serviceMetadata) {
        this.odata = odata;
    }

    @Override
    public void processError(ODataRequest request, ODataResponse response, ODataServerError error,
                             ContentType responseFormat) {
        Exception ex = error.getException();
        if (ex instanceof ODataApplicationException || ex instanceof ODataLibraryException || ex == null) {
            if (error.getStatusCode() >= 500) {
                log.error("OData request {} failed: {}", request.getRawRequestUri(), error.getMessage(), ex);
            } else {
                log.debug("OData request {} rejected with {}: {}", request.getRawRequestUri(), error.getStatusCode(),
                        error.getMessage());
            }
        } else {
            log.error("Unexpected error on OData request {}", request.getRawRequestUri(), ex);
            error.setStatusCode(HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
            error.setMessage("Internal server error");
        }
        if (error.getCode() == null) {
            error.setCode(String.valueOf(error.getStatusCode()));
        }
        if (error.getStatusCode() == HttpStatusCode.UNAUTHORIZED.getStatusCode()) {
            response.setHeader(HttpHeader.WWW_AUTHENTICATE, BASIC_CHALLENGE);
        }
        if (error.getStatusCode() == HttpStatusCode.SERVICE_UNAVAILABLE.getStatusCode()) {
            response.setHeader("Retry-After", "5");
        }
        ContentType format = responseFormat == null ? ContentType.JSON : responseFormat;
        try {
            response.setContent(odata.createSerializer(format).error(error).getContent());
        } catch (SerializerException e) {
            String fallback = "{\"error\":{\"code\":\"" + error.getStatusCode() + "\",\"message\":\""
                    + error.getMessage().replace("\"", "'") + "\"}}";
            response.setContent(new ByteArrayInputStream(fallback.getBytes(StandardCharsets.UTF_8)));
            format = ContentType.JSON;
        }
        response.setStatusCode(error.getStatusCode());
        response.setHeader(HttpHeader.CONTENT_TYPE, format.toContentTypeString());
    }
}
