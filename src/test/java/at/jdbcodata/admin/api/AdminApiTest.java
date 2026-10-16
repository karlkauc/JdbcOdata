package at.jdbcodata.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AdminApiTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void datasourceCrudAndConnectionTest() throws Exception {
        HttpResponse<String> invalid = send("POST", "/api/v1/datasources", "{\"name\":\"\",\"jdbcUrl\":\"\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"fields\"").contains("name");

        String body = """
                {"name":"crud_%s","description":"d","jdbcUrl":"jdbc:h2:mem:crud;DB_CLOSE_DELAY=-1",
                 "driverClass":"","defaultTimeoutSec":5,"maxRows":100}""".formatted(UUID.randomUUID().toString().substring(0, 8));
        HttpResponse<String> created = send("POST", "/api/v1/datasources", body, ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode ds = json.readTree(created.body());
        String id = ds.get("id").asString();
        assertThat(ds.get("driverClass").isNull()).isTrue();
        assertThat(ds.get("maxConcurrent").asInt()).isEqualTo(10);

        assertThat(send("POST", "/api/v1/datasources", body, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(409);

        HttpResponse<String> got = get("/api/v1/datasources/" + id, ADMIN, ADMIN_PASSWORD);
        assertThat(got.statusCode()).isEqualTo(200);
        assertThat(json.readTree(got.body()).get("description").asString()).isEqualTo("d");

        String updated = body.replace("\"description\":\"d\"", "\"description\":\"changed\"");
        HttpResponse<String> put = send("PUT", "/api/v1/datasources/" + id, updated, ADMIN, ADMIN_PASSWORD);
        assertThat(put.statusCode()).isEqualTo(200);
        assertThat(json.readTree(put.body()).get("description").asString()).isEqualTo("changed");

        HttpResponse<String> ok = send("POST", "/api/v1/datasources/" + id + "/test",
                "{\"username\":\"sa\",\"password\":\"\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(ok.statusCode()).isEqualTo(200);
        JsonNode result = json.readTree(ok.body());
        assertThat(result.get("ok").asBoolean()).isTrue();
        assertThat(result.get("productName").asString()).isEqualTo("H2");

        HttpResponse<String> wrong = send("POST", "/api/v1/datasources/" + id + "/test",
                "{\"username\":\"sa\",\"password\":\"wrong\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(json.readTree(wrong.body()).get("ok").asBoolean()).isFalse();
        assertThat(json.readTree(wrong.body()).get("error").asString()).contains("SQLState");

        String badDriver = updated.replace("\"driverClass\":\"\"", "\"driverClass\":\"no.such.Driver\"");
        send("PUT", "/api/v1/datasources/" + id, badDriver, ADMIN, ADMIN_PASSWORD);
        HttpResponse<String> noDriver = send("POST", "/api/v1/datasources/" + id + "/test",
                "{\"username\":\"sa\",\"password\":\"\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(json.readTree(noDriver.body()).get("error").asString()).contains("driver class");

        assertThat(send("DELETE", "/api/v1/datasources/" + id, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/datasources/" + id, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/api/v1/datasources/" + id, body, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/v1/datasources/" + id, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
    }

    @Test
    void referencedDatasourceCannotBeDeleted() throws Exception {
        String demoId = findByName("/api/v1/datasources", "demo");
        HttpResponse<String> r = send("DELETE", "/api/v1/datasources/" + demoId, null, ADMIN, ADMIN_PASSWORD);
        assertThat(r.statusCode()).isEqualTo(409);
        assertThat(r.body()).contains("referenced");
    }

    @Test
    void queryReadAndSnapshot() throws Exception {
        String salesId = findByName("/api/v1/queries", "Sales");
        HttpResponse<String> got = get("/api/v1/queries/" + salesId, ADMIN, ADMIN_PASSWORD);
        assertThat(got.statusCode()).isEqualTo(200);
        JsonNode q = json.readTree(got.body());
        assertThat(q.get("hasSnapshot").asBoolean()).isTrue();
        assertThat(q.get("columns").size()).isEqualTo(8);
        assertThat(q.get("parameters").get(0).get("name").asString()).isEqualTo("p_year");
        assertThat(q.get("feedPath").asString()).isEqualTo("/odata/Sales");

        assertThat(get("/api/v1/queries/" + UUID.randomUUID(), ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);

        HttpResponse<String> wrong = send("POST", "/api/v1/queries/" + salesId + "/snapshot",
                "{\"username\":\"demo\",\"password\":\"wrong\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(wrong.statusCode()).isEqualTo(401);

        HttpResponse<String> badParam = send("POST", "/api/v1/queries/" + salesId + "/snapshot",
                "{\"username\":\"demo\",\"password\":\"demo\",\"parameters\":{\"p_year\":\"x\"}}", ADMIN, ADMIN_PASSWORD);
        assertThat(badParam.statusCode()).isEqualTo(400);

        HttpResponse<String> ok = send("POST", "/api/v1/queries/" + salesId + "/snapshot",
                "{\"username\":\"demo\",\"password\":\"demo\",\"parameters\":{\"p_year\":\"2024\"}}", ADMIN, ADMIN_PASSWORD);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ok.body()).get("hasSnapshot").asBoolean()).isTrue();
        assertThat(json.readTree(ok.body()).get("version").asInt()).isEqualTo(1);
    }

    private String findByName(String path, String name) throws Exception {
        for (JsonNode n : json.readTree(get(path, ADMIN, ADMIN_PASSWORD).body())) {
            if (name.equals(n.get("name").asString())) return n.get("id").asString();
        }
        throw new AssertionError("no entry named " + name);
    }
}
