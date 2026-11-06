package at.jdbcodata.transfer;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ConfigTransferTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final HttpClient client = HttpClient.newHttpClient();

    @Autowired
    private AdminUserService accounts;
    @Autowired
    private QueryService queries;
    @Autowired
    private FixedCredentialService fixedCredentials;
    @Autowired
    private DatasourceService datasources;
    @Autowired
    private ServiceUserService serviceUsers;

    /** Suffixes of entries a test created; removed afterwards, the context is shared with other test classes. */
    private final List<String> created = new ArrayList<>();

    @AfterEach
    void removeCreatedEntries() {
        for (String s : created) {
            queries.findAll().stream().filter(q -> q.name().endsWith(s)).forEach(q -> queries.delete(q.id()));
            fixedCredentials.findAll().stream().filter(c -> c.name().endsWith(s)).forEach(c -> fixedCredentials.delete(c.id()));
            datasources.findAll().stream().filter(d -> d.name().endsWith(s)).forEach(d -> datasources.delete(d.id()));
            serviceUsers.findAllGroups().stream().filter(g -> g.name().endsWith(s)).forEach(g -> serviceUsers.deleteGroup(g.id()));
        }
    }

    @Test
    void exportIsYamlWithoutSecrets() throws Exception {
        HttpResponse<String> export = get("/api/v1/config", ADMIN, ADMIN_PASSWORD);
        assertThat(export.statusCode()).isEqualTo(200);
        assertThat(export.headers().firstValue("Content-Type").orElse("")).startsWith("application/yaml");
        assertThat(export.headers().firstValue("Content-Disposition").orElse("")).contains("jdbcodata-config-");
        assertThat(export.body()).startsWith("format: 1\n").contains("\ndatasources:\n", "\nqueries:\n", "sql: |");
        assertThat(export.body().toLowerCase()).doesNotContain("password:");
        assertThat(get("/api/v1/config", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void reimportingAnExportChangesNothing() throws Exception {
        String export = get("/api/v1/config", ADMIN, ADMIN_PASSWORD).body();
        String versions = get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body();

        JsonNode result = importConfig(export, "dryRun=false", 200);
        assertThat(result.get("applied").asBoolean()).isTrue();
        for (JsonNode change : result.get("changes")) {
            assertThat(change.get("action").asString()).as(change.toString()).isIn("UNCHANGED", "NOT_IN_FILE");
        }
        assertThat(get("/api/v1/queries", ADMIN, ADMIN_PASSWORD).body()).isEqualTo(versions);
    }

    @Test
    void dryRunReportsChangesAndLeavesNothingBehind() throws Exception {
        String s = suffix();
        String audit = get("/api/v1/audit?limit=5", ADMIN, ADMIN_PASSWORD).body();

        JsonNode result = importConfig(newConfig(s), "", 200);
        assertThat(result.get("dryRun").asBoolean()).isTrue();
        assertThat(result.get("applied").asBoolean()).isFalse();
        assertThat(actions(result)).contains("group:grp_" + s + ":CREATE", "datasource:ds_" + s + ":CREATE",
                "fixedCredential:tech_" + s + ":CREATE", "query:Q_" + s + ":CREATE");

        assertThat(get("/api/v1/datasources", ADMIN, ADMIN_PASSWORD).body()).doesNotContain("ds_" + s);
        assertThat(get("/api/v1/groups", ADMIN, ADMIN_PASSWORD).body()).doesNotContain("grp_" + s);
        assertThat(get("/api/v1/audit?limit=5", ADMIN, ADMIN_PASSWORD).body()).isEqualTo(audit);
    }

    @Test
    void importCreatesThenUpdatesKeepingTheTargetsConnection() throws Exception {
        String s = suffix();
        JsonNode created = importConfig(newConfig(s), "dryRun=false", 200);
        assertThat(created.get("applied").asBoolean()).isTrue();

        JsonNode query = find("/api/v1/queries", "Q_" + s);
        assertThat(query.get("executionMode").asString()).isEqualTo("FIXED");
        assertThat(query.get("fixedCredentialName").asString()).isEqualTo("tech_" + s);
        assertThat(query.get("columns")).hasSize(2);
        assertThat(query.at("/columns/0/key").asBoolean()).isTrue();
        assertThat(query.at("/columns/1/propertyName").asString()).isEqualTo("Name");
        JsonNode grants = json.readTree(get("/api/v1/queries/" + query.get("id").asString() + "/grants", ADMIN, ADMIN_PASSWORD).body());
        assertThat(grants).hasSize(1);
        assertThat(grants.at("/0/principalName").asString()).isEqualTo("grp_" + s);
        assertThat(find("/api/v1/groups", "grp_" + s).get("description").asString()).isEqualTo("Ümläut group");

        // a production file: other URL and a changed limit; the URL stays
        String changed = newConfig(s).replace("jdbc:h2:mem:cfg_" + s, "jdbc:h2:mem:prod_" + s)
                .replace("maxRows: 100", "maxRows: 200");
        JsonNode dryRun = importConfig(changed, "", 200);
        JsonNode ds = change(dryRun, "datasource", "ds_" + s);
        assertThat(ds.get("action").asString()).isEqualTo("UPDATE");
        assertThat(ds.get("fields")).extracting(JsonNode::asString).containsExactly("maxRows");
        assertThat(ds.get("note").asString()).contains("Connection kept");
        assertThat(change(dryRun, "query", "Q_" + s).get("action").asString()).isEqualTo("UNCHANGED");
        assertThat(change(dryRun, "fixedCredential", "tech_" + s).get("action").asString()).isEqualTo("UNCHANGED");

        importConfig(changed, "dryRun=false", 200);
        JsonNode kept = find("/api/v1/datasources", "ds_" + s);
        assertThat(kept.get("maxRows").asInt()).isEqualTo(200);
        assertThat(kept.get("jdbcUrl").asString()).isEqualTo("jdbc:h2:mem:cfg_" + s);

        importConfig(changed, "dryRun=false&overwriteConnections=true", 200);
        assertThat(find("/api/v1/datasources", "ds_" + s).get("jdbcUrl").asString()).isEqualTo("jdbc:h2:mem:prod_" + s);

        // changed SQL with the file's snapshot: new version, columns from the file
        String newSql = changed.replace("SELECT id, name FROM t", "SELECT id, name FROM t WHERE id > 0");
        JsonNode sql = importConfig(newSql, "dryRun=false", 200);
        assertThat(change(sql, "query", "Q_" + s).get("fields")).extracting(JsonNode::asString).containsExactly("sql");
        JsonNode updated = find("/api/v1/queries", "Q_" + s);
        assertThat(updated.get("version").asInt()).isEqualTo(2);
        assertThat(updated.get("columns")).hasSize(2);
    }

    @Test
    void anErrorRollsBackTheWholeImport() throws Exception {
        String s = suffix();
        String broken = newConfig(s).replace("datasource: ds_" + s + "\n    sql:", "datasource: missing_" + s + "\n    sql:");
        JsonNode result = importConfig(broken, "dryRun=false", 400);
        assertThat(result.get("applied").asBoolean()).isFalse();
        assertThat(result.get("errors").toString()).contains("Query 'Q_" + s + "'", "Unknown datasource 'missing_" + s + "'");
        assertThat(get("/api/v1/groups", ADMIN, ADMIN_PASSWORD).body()).doesNotContain("grp_" + s);
        assertThat(get("/api/v1/datasources", ADMIN, ADMIN_PASSWORD).body()).doesNotContain("ds_" + s);
    }

    @Test
    void newTechnicalAccountNeedsAPasswordAndUnknownUsersOnlyWarn() throws Exception {
        String s = suffix();
        JsonNode noPassword = importConfig(newConfig(s).replace("    dbPassword: secret\n", ""), "", 400);
        assertThat(noPassword.get("errors").toString()).contains("Technical account 'tech_" + s + "' is new and needs dbPassword");

        String withUser = newConfig(s).replace("      - type: GROUP\n", "      - type: USER\n        name: nobody_" + s + "\n      - type: GROUP\n");
        JsonNode warned = importConfig(withUser, "", 200);
        assertThat(warned.get("warnings").toString()).contains("service user 'nobody_" + s + "' does not exist here");
    }

    @Test
    void pruneDeletesOnlyWhatIsMissingFromThePresentSections() throws Exception {
        String s = suffix();
        assertThat(send("POST", "/api/v1/groups", "{\"name\":\"gone_" + s + "\"}", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(201);
        // groups section with all groups but the new one, no other sections
        StringBuilder file = new StringBuilder("format: 1\ngroups: []\n");
        for (JsonNode g : json.readTree(get("/api/v1/groups", ADMIN, ADMIN_PASSWORD).body())) {
            if (!g.get("name").asString().equals("gone_" + s)) file.append("- name: ").append(g.get("name").asString()).append('\n');
        }
        String yaml = file.toString().replace("groups: []\n- ", "groups:\n- ");

        JsonNode dryRun = importConfig(yaml, "prune=true", 200);
        assertThat(actions(dryRun)).contains("group:gone_" + s + ":DELETE");
        assertThat(actions(dryRun)).noneMatch(a -> a.endsWith(":DELETE") && !a.equals("group:gone_" + s + ":DELETE"));
        assertThat(actions(dryRun)).noneMatch(a -> a.startsWith("query:") || a.startsWith("datasource:"));

        JsonNode withoutPrune = importConfig(yaml, "", 200);
        assertThat(actions(withoutPrune)).contains("group:gone_" + s + ":NOT_IN_FILE");

        importConfig(yaml, "dryRun=false&prune=true", 200);
        assertThat(get("/api/v1/groups", ADMIN, ADMIN_PASSWORD).body()).doesNotContain("gone_" + s);
    }

    @Test
    void rejectsFilesThatAreNoExport() throws Exception {
        assertThat(problem("datasources: []\n").get("detail").asString()).contains("'format: 1' is missing");
        assertThat(problem("format: 2\n").get("detail").asString()).contains("Format 2 is not supported");
        assertThat(problem("format: 1\nqueries:\n  - name: X\n    sqll: SELECT 1\n").get("detail").asString())
                .startsWith("Line 4: ").contains("\"sqll\"");
        assertThat(problem("format: 1\nqueries: [\n").get("detail").asString()).startsWith("Line ");
        // an empty section is no section: nothing to prune
        JsonNode empty = importConfig("format: 1\ngroups:\n", "prune=true", 200);
        assertThat(empty.get("changes")).isEmpty();
    }

    @Test
    void onlyAdminsImport() throws Exception {
        String viewer = "cfgviewer_" + suffix();
        assertThat(send("POST", "/api/v1/admins", "{\"username\":\"%s\",\"password\":\"viewer-pass\",\"role\":\"VIEWER\"}"
                .formatted(viewer), ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(201);
        accounts.changeOwnPassword(viewer, "viewer-pass", "viewer-own");
        assertThat(get("/api/v1/config", viewer, "viewer-own").statusCode()).isEqualTo(200);
        assertThat(post("format: 1\n", "", viewer, "viewer-own").statusCode()).isEqualTo(403);
    }

    // --- helpers -----------------------------------------------------------------------------------------------

    /** Group, datasource, technical account and a FIXED query granted to the group, with a two-column snapshot. */
    private static String newConfig(String s) {
        return """
                format: 1
                groups:
                  - name: grp_%1$s
                    description: Ümläut group
                datasources:
                  - name: ds_%1$s
                    jdbcUrl: jdbc:h2:mem:cfg_%1$s
                    maxRows: 100
                fixedCredentials:
                  - name: tech_%1$s
                    datasource: ds_%1$s
                    dbUsername: sa
                    dbPassword: secret
                queries:
                  - name: Q_%1$s
                    datasource: ds_%1$s
                    sql: SELECT id, name FROM t
                    executionMode: FIXED
                    fixedCredential: tech_%1$s
                    keyMode: COLUMNS
                    columns:
                      - name: ID
                        propertyName: ID
                        jdbcType: 4
                        jdbcTypeName: INTEGER
                        edmType: Edm.Int32
                        key: true
                        visible: true
                      - name: NAME
                        propertyName: Name
                        jdbcType: 12
                        jdbcTypeName: VARCHAR
                        edmType: Edm.String
                        nullable: true
                        visible: true
                    grants:
                      - type: GROUP
                        name: grp_%1$s
                """.formatted(s);
    }

    private JsonNode importConfig(String yaml, String query, int expectedStatus) throws Exception {
        HttpResponse<String> response = post(yaml, query, ADMIN, ADMIN_PASSWORD);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return json.readTree(response.body());
    }

    private JsonNode problem(String yaml) throws Exception {
        return importConfig(yaml, "", 400);
    }

    private HttpResponse<String> post(String yaml, String query, String user, String password) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/config/import?" + query))
                .header("Content-Type", "application/yaml")
                .header("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + password).getBytes()))
                .POST(HttpRequest.BodyPublishers.ofString(yaml))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode find(String path, String name) throws Exception {
        for (JsonNode n : json.readTree(get(path, ADMIN, ADMIN_PASSWORD).body())) {
            if (name.equals(n.get("name").asString())) return n;
        }
        throw new AssertionError(name + " not found in " + path);
    }

    private static JsonNode change(JsonNode result, String type, String name) {
        for (JsonNode c : result.get("changes")) {
            if (type.equals(c.get("type").asString()) && name.equals(c.get("name").asString())) return c;
        }
        throw new AssertionError(type + " " + name + " not in " + result);
    }

    private static List<String> actions(JsonNode result) {
        List<String> out = new ArrayList<>();
        result.get("changes").forEach(c -> out.add(c.get("type").asString() + ":" + c.get("name").asString() + ":" + c.get("action").asString()));
        return out;
    }

    private String suffix() {
        String s = UUID.randomUUID().toString().substring(0, 8);
        created.add(s);
        return s;
    }
}
