package at.jdbcodata.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class QueryApiTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void crudTestVersionsAndColumns() throws Exception {
        String demoId = findByName("/api/v1/datasources", "demo");
        String name = "Api_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');

        HttpResponse<String> invalid = send("POST", "/api/v1/queries", "{\"name\":\"\",\"sql\":\"\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("\"fields\"");

        String body = """
                {"name":"%s","description":"api","datasourceId":"%s",
                 "sql":"SELECT customer, amount FROM sales WHERE sale_year = :y",
                 "parameters":[{"name":"y","type":"INT","required":true}],
                 "cacheEnabled":false,"keyMode":"ROWID"}""".formatted(name, demoId);
        HttpResponse<String> created = send("POST", "/api/v1/queries", body, ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode q = json.readTree(created.body());
        String id = q.get("id").asString();
        assertThat(q.get("version").asInt()).isEqualTo(1);
        assertThat(q.get("hasSnapshot").asBoolean()).isFalse();
        assertThat(q.get("parameters").get(0).get("required").asBoolean()).isTrue();

        HttpResponse<String> badSql = send("POST", "/api/v1/queries", body.replace(name, name + "x").replace("SELECT customer", "DROP TABLE x; SELECT customer"), ADMIN, ADMIN_PASSWORD);
        assertThat(badSql.statusCode()).isEqualTo(400);

        assertThat(get("/odata/" + name, "demo", "demo").statusCode()).as("no snapshot yet").isEqualTo(404);

        HttpResponse<String> test = send("POST", "/api/v1/queries/" + id + "/test",
                "{\"username\":\"demo\",\"password\":\"demo\",\"parameters\":{\"y\":\"2024\"}}", ADMIN, ADMIN_PASSWORD);
        assertThat(test.statusCode()).as(test.body()).isEqualTo(200);
        JsonNode t = json.readTree(test.body());
        assertThat(t.get("rowCount").asInt()).isEqualTo(12);
        assertThat(t.get("truncated").asBoolean()).isFalse();
        assertThat(t.get("columns").size()).isEqualTo(2);
        assertThat(t.get("rows").get(0).get("customer").asString()).isEqualTo("Alpha GmbH");
        assertThat(t.get("previewLimit").asInt()).isEqualTo(100);

        HttpResponse<String> missing = send("POST", "/api/v1/queries/" + id + "/test",
                "{\"username\":\"demo\",\"password\":\"demo\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(missing.statusCode()).isEqualTo(400);

        HttpResponse<String> adHoc = send("POST", "/api/v1/queries/test", """
                {"query":{"name":"AdHoc","datasourceId":"%s","sql":"SELECT id FROM sales"},
                 "username":"demo","password":"demo"}""".formatted(demoId), ADMIN, ADMIN_PASSWORD);
        assertThat(adHoc.statusCode()).as(adHoc.body()).isEqualTo(200);
        assertThat(json.readTree(adHoc.body()).get("rowCount").asInt()).isEqualTo(36);

        HttpResponse<String> adHocBad = send("POST", "/api/v1/queries/test", """
                {"query":{"name":"AdHoc","datasourceId":"%s","sql":"SELECT nope FROM sales"},
                 "username":"demo","password":"demo"}""".formatted(demoId), ADMIN, ADMIN_PASSWORD);
        assertThat(adHocBad.statusCode()).isEqualTo(502);

        HttpResponse<String> snap = send("POST", "/api/v1/queries/" + id + "/snapshot",
                "{\"username\":\"demo\",\"password\":\"demo\",\"parameters\":{\"y\":\"2025\"}}", ADMIN, ADMIN_PASSWORD);
        assertThat(snap.statusCode()).isEqualTo(200);
        assertThat(json.readTree(snap.body()).get("hasSnapshot").asBoolean()).isTrue();

        HttpResponse<String> feed = get("/odata/" + name + "?y=2025&$top=1", "demo", "demo");
        assertThat(feed.statusCode()).isEqualTo(200);

        HttpResponse<String> cols = send("PUT", "/api/v1/queries/" + id + "/columns",
                "{\"columns\":[{\"propertyName\":\"customer\",\"key\":true,\"visible\":true},{\"propertyName\":\"amount\",\"key\":false,\"visible\":false}]}",
                ADMIN, ADMIN_PASSWORD);
        assertThat(cols.statusCode()).isEqualTo(200);
        JsonNode c = json.readTree(cols.body()).get("columns");
        assertThat(c.get(0).get("key").asBoolean()).isTrue();
        assertThat(c.get(1).get("visible").asBoolean()).isFalse();

        HttpResponse<String> updated = send("PUT", "/api/v1/queries/" + id,
                body.replace("SELECT customer, amount", "SELECT customer, amount, region"), ADMIN, ADMIN_PASSWORD);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).get("version").asInt()).isEqualTo(2);
        assertThat(json.readTree(updated.body()).get("hasSnapshot").asBoolean()).isFalse();

        HttpResponse<String> versions = get("/api/v1/queries/" + id + "/versions", ADMIN, ADMIN_PASSWORD);
        assertThat(versions.statusCode()).isEqualTo(200);
        JsonNode v = json.readTree(versions.body());
        assertThat(v.size()).isEqualTo(2);
        assertThat(v.get(0).get("version").asInt()).isEqualTo(2);
        assertThat(v.get(1).get("sql").asString()).doesNotContain("region");
        assertThat(v.get(0).get("changedBy").asString()).isEqualTo("admin");

        HttpResponse<String> restored = send("POST", "/api/v1/queries/" + id + "/versions/1/restore", null, ADMIN, ADMIN_PASSWORD);
        assertThat(restored.statusCode()).isEqualTo(200);
        assertThat(json.readTree(restored.body()).get("version").asInt()).isEqualTo(3);
        assertThat(json.readTree(restored.body()).get("sql").asString()).doesNotContain("region");
        assertThat(send("POST", "/api/v1/queries/" + id + "/versions/42/restore", null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);

        assertThat(send("POST", "/api/v1/queries", body, ADMIN, ADMIN_PASSWORD).statusCode()).as("duplicate").isEqualTo(409);

        assertThat(send("DELETE", "/api/v1/queries/" + id, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/queries/" + id, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/queries/" + id + "/versions", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
    }

    private String findByName(String path, String name) throws Exception {
        for (JsonNode n : json.readTree(get(path, ADMIN, ADMIN_PASSWORD).body())) {
            if (name.equals(n.get("name").asString())) return n.get("id").asString();
        }
        throw new AssertionError("no entry named " + name);
    }
}
