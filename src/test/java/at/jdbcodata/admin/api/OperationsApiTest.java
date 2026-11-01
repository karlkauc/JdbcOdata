package at.jdbcodata.admin.api;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OperationsApiTest extends DemoProfileTest {

    @Autowired
    private AdminUserService accounts;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void serviceUsersGroupsAndFixedCredentialsOverApi() throws Exception {
        String demoId = findByName("/api/v1/datasources", "demo");
        String name = "svc_api_" + UUID.randomUUID().toString().substring(0, 8);

        assertThat(send("POST", "/api/v1/users", "{\"username\":\"\"}", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);
        HttpResponse<String> created = send("POST", "/api/v1/users",
                "{\"username\":\"%s\",\"displayName\":\"Api\",\"password\":\"password-1\"}".formatted(name), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = json.readTree(created.body()).get("id").asString();
        assertThat(created.body()).doesNotContain("password-1");

        HttpResponse<String> group = send("POST", "/api/v1/groups", "{\"name\":\"grp_api_" + name + "\",\"description\":\"d\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(group.statusCode()).isEqualTo(201);
        String groupId = json.readTree(group.body()).get("id").asString();
        assertThat(get("/api/v1/groups", ADMIN, ADMIN_PASSWORD).body()).contains(groupId);

        HttpResponse<String> updated = send("PUT", "/api/v1/users/" + id,
                "{\"username\":\"%s\",\"forcePassthrough\":true,\"enabled\":true,\"groupIds\":[\"%s\"]}".formatted(name, groupId), ADMIN, ADMIN_PASSWORD);
        assertThat(updated.statusCode()).as(updated.body()).isEqualTo(200);
        assertThat(json.readTree(updated.body()).get("forcePassthrough").asBoolean()).isTrue();
        assertThat(json.readTree(updated.body()).get("groups").size()).isEqualTo(1);
        assertThat(get("/api/v1/users/" + id, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/users", ADMIN, ADMIN_PASSWORD).body()).contains(name);

        HttpResponse<String> cred = send("PUT", "/api/v1/users/" + id + "/credentials/" + demoId,
                "{\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(cred.statusCode()).as(cred.body()).isEqualTo(200);
        assertThat(cred.body()).contains("\"dbUsername\":\"demo\"").doesNotContain("\"dbPassword\"");
        assertThat(json.readTree(get("/api/v1/users/" + id + "/credentials", ADMIN, ADMIN_PASSWORD).body()).size()).isEqualTo(1);
        assertThat(send("DELETE", "/api/v1/users/" + id + "/credentials/" + demoId, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/v1/users/" + id + "/credentials/" + demoId, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);

        HttpResponse<String> fixed = send("POST", "/api/v1/fixed-credentials",
                "{\"name\":\"tech_api_%s\",\"datasourceId\":\"%s\",\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}".formatted(name, demoId), ADMIN, ADMIN_PASSWORD);
        assertThat(fixed.statusCode()).as(fixed.body()).isEqualTo(201);
        String fixedId = json.readTree(fixed.body()).get("id").asString();
        assertThat(fixed.body()).doesNotContain("dbPassword");
        assertThat(get("/api/v1/fixed-credentials/" + fixedId, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/fixed-credentials", ADMIN, ADMIN_PASSWORD).body()).contains(fixedId);
        HttpResponse<String> fixedUpd = send("PUT", "/api/v1/fixed-credentials/" + fixedId,
                "{\"name\":\"tech_api_%s\",\"description\":\"x\",\"datasourceId\":\"%s\",\"dbUsername\":\"demo\"}".formatted(name, demoId), ADMIN, ADMIN_PASSWORD);
        assertThat(fixedUpd.statusCode()).isEqualTo(200);

        // FIXED query with grants over the API
        String qName = "FixedApi_" + name.substring(8);
        HttpResponse<String> q = send("POST", "/api/v1/queries", """
                {"name":"%s","datasourceId":"%s","sql":"SELECT id FROM sales","executionMode":"FIXED","fixedCredentialId":"%s"}"""
                .formatted(qName, demoId, fixedId), ADMIN, ADMIN_PASSWORD);
        assertThat(q.statusCode()).as(q.body()).isEqualTo(201);
        String qId = json.readTree(q.body()).get("id").asString();
        assertThat(json.readTree(q.body()).get("fixedCredentialName").asString()).startsWith("tech_api_");
        assertThat(send("DELETE", "/api/v1/fixed-credentials/" + fixedId, null, ADMIN, ADMIN_PASSWORD).statusCode()).as("in use").isEqualTo(409);
        HttpResponse<String> grants = send("PUT", "/api/v1/queries/" + qId + "/grants",
                "{\"grants\":[{\"principalType\":\"USER\",\"principalId\":\"%s\"},{\"principalType\":\"GROUP\",\"principalId\":\"%s\"},{\"principalType\":\"ALL_AUTHENTICATED\"}]}".formatted(id, groupId), ADMIN, ADMIN_PASSWORD);
        assertThat(grants.statusCode()).as(grants.body()).isEqualTo(200);
        assertThat(json.readTree(grants.body()).size()).isEqualTo(3);
        assertThat(json.readTree(get("/api/v1/queries/" + qId + "/grants", ADMIN, ADMIN_PASSWORD).body()).size()).isEqualTo(3);

        assertThat(send("DELETE", "/api/v1/queries/" + qId, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/v1/fixed-credentials/" + fixedId, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/v1/users/" + id, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/v1/groups/" + groupId, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(get("/api/v1/users/" + id, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
    }

    @Test
    void adminAccountsAndRoles() throws Exception {
        String viewer = "viewer_" + UUID.randomUUID().toString().substring(0, 8);
        HttpResponse<String> created = send("POST", "/api/v1/admins",
                "{\"username\":\"%s\",\"password\":\"viewer-pass\",\"role\":\"VIEWER\"}".formatted(viewer), ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String id = json.readTree(created.body()).get("id").asString();
        assertThat(created.body()).doesNotContain("viewer-pass");

        assertThat(get("/api/v1/queries", viewer, "viewer-pass").statusCode()).as("temporary password").isEqualTo(403);
        accounts.changeOwnPassword(viewer, "viewer-pass", "viewer-own");

        assertThat(get("/api/v1/queries", viewer, "viewer-own").statusCode()).as("viewer may read").isEqualTo(200);
        assertThat(get("/api/v1/stats/summary", viewer, "viewer-own").statusCode()).isEqualTo(200);
        assertThat(send("DELETE", "/api/v1/cache", null, viewer, "viewer-own").statusCode()).as("viewer may not write").isEqualTo(403);
        assertThat(send("POST", "/api/v1/groups", "{\"name\":\"x_y\"}", viewer, "viewer-own").statusCode()).isEqualTo(403);

        assertThat(send("POST", "/api/v1/admins", "{\"username\":\"%s\",\"password\":\"viewer-pass\"}".formatted(viewer), ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/v1/admins", "{\"username\":\"ok_name\",\"password\":\"short\"}", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);

        HttpResponse<String> promoted = send("PUT", "/api/v1/admins/" + id, "{\"username\":\"%s\",\"role\":\"ADMIN\",\"enabled\":true,\"password\":\"viewer-pass2\"}".formatted(viewer), ADMIN, ADMIN_PASSWORD);
        assertThat(promoted.statusCode()).isEqualTo(200);
        accounts.changeOwnPassword(viewer, "viewer-pass2", "viewer-own2");
        assertThat(get("/api/v1/queries", viewer, "viewer-own2").statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/queries", viewer, "viewer-own").statusCode()).isEqualTo(401);

        String adminId = null;
        for (JsonNode a : json.readTree(get("/api/v1/admins", ADMIN, ADMIN_PASSWORD).body())) {
            if (a.get("username").asString().equals(ADMIN)) adminId = a.get("id").asString();
        }
        assertThat(adminId).isNotNull();
        assertThat(send("DELETE", "/api/v1/admins/" + adminId, null, ADMIN, ADMIN_PASSWORD).statusCode()).as("own account").isEqualTo(409);

        assertThat(send("DELETE", "/api/v1/admins/" + id, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/v1/admins/" + id, null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(404);
        assertThat(send("PUT", "/api/v1/admins/" + adminId, "{\"username\":\"%s\",\"role\":\"VIEWER\"}".formatted(ADMIN), ADMIN, ADMIN_PASSWORD).statusCode())
                .as("last admin keeps role").isEqualTo(409);
        assertThat(get("/api/v1/audit?limit=5", ADMIN, ADMIN_PASSWORD).body()).contains("ADMIN_USER");
    }

    @Test
    void prometheusMetricsAreExposedToAdmins() throws Exception {
        get("/odata/Sales?$top=1", "demo", "demo");
        HttpResponse<String> metrics = get("/actuator/prometheus", ADMIN, ADMIN_PASSWORD);
        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains("jdbcodata_executions_total").contains("jdbcodata_execution_duration_seconds");
        assertThat(metrics.body()).contains("jdbcodata_pools ", "jdbcodata_pool_connections_pending ",
                "jdbcodata_executions_running ", "jdbcodata_ratelimit_rejected_total ", "jdbcodata_executionlog_dropped_total ");
        assertThat(get("/actuator/prometheus", null, null).statusCode()).isIn(401, 302);
    }

    private String findByName(String path, String name) throws Exception {
        for (JsonNode n : json.readTree(get(path, ADMIN, ADMIN_PASSWORD).body())) {
            if (name.equals(n.get("name").asString())) return n.get("id").asString();
        }
        throw new AssertionError("no entry named " + name);
    }
}
