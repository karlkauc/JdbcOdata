package at.jdbcodata.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenApiTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void specNeedsAnAdminLogin() throws Exception {
        assertThat(get("/api/v1/openapi", null, null).statusCode()).isEqualTo(401);
        assertThat(get("/api/v1/openapi", ADMIN, "wrong").statusCode()).isEqualTo(401);
    }

    @Test
    void describesEveryAdminApiEndpointAndNothingElse() throws Exception {
        HttpResponse<String> response = get("/api/v1/openapi", ADMIN, ADMIN_PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode spec = json.readTree(response.body());
        assertThat(spec.get("openapi").asString()).startsWith("3.");
        assertThat(spec.at("/info/title").asString()).isEqualTo("JdbcOdata admin API");

        Set<String> described = new HashSet<>(spec.get("paths").propertyNames());
        Set<String> mapped = new HashSet<>();
        mappings.getHandlerMethods().forEach((info, method) -> info.getPatternValues().stream()
                .filter(p -> p.startsWith("/api/v1/") && !p.startsWith("/api/v1/openapi"))
                .forEach(mapped::add));
        assertThat(mapped).isNotEmpty();
        assertThat(described).containsExactlyInAnyOrderElementsOf(mapped);

        assertThat(spec.at("/components/securitySchemes/basic/scheme").asString()).isEqualTo("basic");
        assertThat(spec.at("/components/securitySchemes/apiKey/scheme").asString()).isEqualTo("bearer");
        assertThat(spec.at("/paths/~1api~1v1~1queries/get/tags/0").asString()).isEqualTo("Queries");
        JsonNode create = spec.at("/paths/~1api~1v1~1queries/post");
        assertThat(create.at("/requestBody/content/application~1json/schema/$ref").asString()).endsWith("/QueryRequest");
        assertThat(create.at("/responses").propertyNames()).contains("201", "400", "401", "403", "409").doesNotContain("200", "404");
        assertThat(spec.at("/paths/~1api~1v1~1queries~1{id}/get/responses").propertyNames()).contains("200", "404").doesNotContain("409");
        assertThat(spec.at("/components/schemas/Problem/properties/fields").isMissingNode()).isFalse();
    }

    @Test
    void responsesNeverDescribeStoredSecrets() throws Exception {
        String body = get("/api/v1/openapi", ADMIN, ADMIN_PASSWORD).body();
        JsonNode schemas = json.readTree(body).at("/components/schemas");
        schemas.properties().forEach(schema -> {
            if (schema.getKey().endsWith("Request")) return; // write-only input
            assertThat(schema.getValue().at("/properties").propertyNames())
                    .as(schema.getKey())
                    .noneMatch(p -> p.equalsIgnoreCase("password") || p.toLowerCase().contains("hash")
                            || p.toLowerCase().contains("encrypted"));
        });
    }

    @Test
    void yamlVariant() throws Exception {
        HttpResponse<String> yaml = get("/api/v1/openapi.yaml", ADMIN, ADMIN_PASSWORD);
        assertThat(yaml.statusCode()).isEqualTo(200);
        assertThat(yaml.body()).startsWith("openapi: 3.");
    }
}
