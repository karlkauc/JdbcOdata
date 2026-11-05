package at.jdbcodata.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.MapSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI description of the admin API under {@code /api/v1/openapi} (FR-62). Only {@code /api/v1/**} is described;
 * the OData feed has its own {@code $metadata}.
 */
@Configuration
public class OpenApiConfig {

    static final String BASIC = "basic";
    static final String API_KEY = "apiKey";
    static final String PROBLEM = "Problem";

    @Bean
    OpenAPI adminApi() {
        String version = OpenApiConfig.class.getPackage().getImplementationVersion();
        return new OpenAPI()
                .info(new Info()
                        .title("JdbcOdata admin API")
                        .version(version == null ? "dev" : version)
                        .description("""
                                Manages datasources, queries, service users, API keys, cache, statistics, audit log \
                                and admin accounts. GET needs the role VIEWER or ADMIN, every other method ADMIN. \
                                API keys cannot manage API keys.""")
                        .license(new License().name("MIT").url("https://opensource.org/licenses/MIT")))
                .components(new Components()
                        .addSecuritySchemes(BASIC, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")
                                .description("Admin account, or an API key with an admin role as password and `apikey` as user name."))
                        .addSecuritySchemes(API_KEY, new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("bearer")
                                .description("API key with an admin role, `Authorization: Bearer jdo_…`")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY));
    }

    /**
     * Errors from {@code ApiExceptionHandler} are RFC 9457 problem details, which springdoc does not see; 401 and 403
     * come from Spring Security. 404 only where the path names an entity, 409 only on writes (name taken, in use).
     */
    @Bean
    OpenApiCustomizer problemResponses() {
        return api -> {
            api.getComponents().addSchemas(PROBLEM, new ObjectSchema()
                    .addProperty("type", new StringSchema())
                    .addProperty("title", new StringSchema())
                    .addProperty("status", new IntegerSchema())
                    .addProperty("detail", new StringSchema())
                    .addProperty("instance", new StringSchema())
                    .addProperty("fields", new MapSchema().additionalProperties(new StringSchema())
                            .description("Validation errors per field")));
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
                ApiResponses responses = operation.getResponses();
                responses.addApiResponse("400", problem("Invalid request or failed validation"));
                responses.addApiResponse("401", new ApiResponse().description("Not signed in"));
                responses.addApiResponse("403", new ApiResponse()
                        .description("Role too low, an API key calling /api-keys, or a password change is due"));
                if (path.contains("{")) {
                    responses.addApiResponse("404", problem("Not found"));
                }
                if (method != PathItem.HttpMethod.GET) {
                    responses.addApiResponse("409", problem("Conflict, e.g. name taken or still in use"));
                }
            }));
        };
    }

    private static ApiResponse problem(String description) {
        return new ApiResponse().description(description).content(new Content().addMediaType("application/problem+json",
                new MediaType().schema(new Schema<>().$ref("#/components/schemas/" + PROBLEM))));
    }
}
