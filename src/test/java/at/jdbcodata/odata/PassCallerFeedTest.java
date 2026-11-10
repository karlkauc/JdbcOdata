package at.jdbcodata.odata;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.query.PassCaller;
import at.jdbcodata.testutil.DemoProfileTest;
import at.jdbcodata.transfer.ConfigYaml;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Two service users behind one technical account, with the caller passed to the database: each gets its own name
 * and groups, also from the result cache, and a query that switches it off gets none.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PassCallerFeedTest extends DemoProfileTest {

    private static final String DEMO_URL = "jdbc:h2:mem:demo-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
    private static final String WHO = "SELECT CAST(@jdbcodata_caller AS VARCHAR(100)) AS who, "
            + "CAST(@jdbcodata_groups AS VARCHAR(200)) AS grps";
    private static final String PASSWORD = "caller-password-1";

    private final JsonMapper json = JsonMapper.builder().build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String anna = "anna_" + suffix;
    private final String bernd = "bernd_" + suffix;
    private final String group = "nord_" + suffix;
    private final String who = "Who_" + suffix;
    private final String whoOff = "WhoOff_" + suffix;
    private String datasourceId;
    private String accountId;
    private String groupId;
    private String annaId;
    private String berndId;
    private String whoId;
    private String whoOffId;

    @BeforeAll
    void createCallers() throws Exception {
        datasourceId = id(post("/api/v1/datasources", """
                {"name":"callers_%s","jdbcUrl":"%s","passCaller":true,"poolSize":1}""".formatted(suffix, DEMO_URL)));
        accountId = id(post("/api/v1/fixed-credentials", """
                {"name":"callers_%s","datasourceId":"%s","dbUsername":"demo","dbPassword":"demo"}""".formatted(suffix, datasourceId)));
        groupId = id(post("/api/v1/groups", "{\"name\":\"%s\"}".formatted(group)));
        annaId = id(post("/api/v1/users", """
                {"username":"%s","password":"%s","groupIds":["%s"]}""".formatted(anna, PASSWORD, groupId)));
        berndId = id(post("/api/v1/users", "{\"username\":\"%s\",\"password\":\"%s\"}".formatted(bernd, PASSWORD)));
        whoId = feed(who, "INHERIT");
        whoOffId = feed(whoOff, "OFF");
    }

    private String feed(String name, String passCaller) throws Exception {
        String id = id(post("/api/v1/queries", """
                {"name":"%s","datasourceId":"%s","sql":"%s","executionMode":"FIXED","fixedCredentialId":"%s",
                 "cacheEnabled":true,"cacheTtlSec":600,"passCaller":"%s"}""".formatted(name, datasourceId, WHO, accountId, passCaller)));
        HttpResponse<String> grants = send("PUT", "/api/v1/queries/" + id + "/grants",
                "{\"grants\":[{\"principalType\":\"ALL_AUTHENTICATED\"}]}", ADMIN, ADMIN_PASSWORD);
        assertThat(grants.statusCode()).as(grants.body()).isEqualTo(200);
        HttpResponse<String> snapshot = post("/api/v1/queries/" + id + "/snapshot", "{\"username\":\"demo\",\"password\":\"demo\"}");
        assertThat(snapshot.statusCode()).as(snapshot.body()).isEqualTo(200);
        return id;
    }

    @AfterAll
    void removeCallers() throws Exception {
        for (String path : new String[] {"/api/v1/queries/" + whoId, "/api/v1/queries/" + whoOffId, "/api/v1/users/" + annaId,
                "/api/v1/users/" + berndId, "/api/v1/groups/" + groupId, "/api/v1/fixed-credentials/" + accountId,
                "/api/v1/datasources/" + datasourceId}) {
            send("DELETE", path, null, ADMIN, ADMIN_PASSWORD);
        }
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return send("POST", path, body, ADMIN, ADMIN_PASSWORD);
    }

    private String id(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return json.readTree(response.body()).get("id").asString();
    }

    private JsonNode row(String feed, String user) throws Exception {
        HttpResponse<String> response = get("/odata/" + feed, user, PASSWORD);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body()).get("value").get(0);
    }

    @Test
    void eachCallerGetsItsOwnNameAndGroupsAlsoFromTheCache() throws Exception {
        for (int i = 0; i < 2; i++) {
            JsonNode a = row(who, anna);
            assertThat(a.get("who").asString()).isEqualTo(anna);
            assertThat(a.get("grps").asString()).isEqualTo(group);
            JsonNode b = row(who, bernd);
            assertThat(b.get("who").asString()).isEqualTo(bernd);
            assertThat(b.get("grps").asString()).isEmpty();
        }
    }

    @Test
    void aQueryCanSwitchItOff() throws Exception {
        row(who, anna);
        assertThat(row(whoOff, anna).get("who").isNull()).isTrue();
    }

    @Test
    void theSettingIsPartOfApiAndExport() throws Exception {
        assertThat(json.readTree(get("/api/v1/datasources/" + datasourceId, ADMIN, ADMIN_PASSWORD).body())
                .get("passCaller").asBoolean()).isTrue();
        assertThat(json.readTree(get("/api/v1/queries/" + whoOffId, ADMIN, ADMIN_PASSWORD).body())
                .get("passCaller").asString()).isEqualTo("OFF");
        String export = get("/api/v1/config", ADMIN, ADMIN_PASSWORD).body();
        assertThat(export).contains("name: callers_" + suffix + "\n").contains("passCaller: true")
                .contains("name: " + whoOff + "\n").contains("passCaller: \"OFF\"");
        // YAML 1.1 reads a bare OFF as false; a hand-written file may still leave out the quotes
        assertThat(ConfigYaml.read("format: 1\nqueries:\n- name: X\n  passCaller: OFF\n").queries().get(0).passCaller())
                .isEqualTo(PassCaller.OFF);
    }
}
