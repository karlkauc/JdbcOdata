package at.jdbcodata.apikey;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.admin.account.AdminRole;
import at.jdbcodata.audit.AuditService;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApiKeyTest extends DemoProfileTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private ApiKeyService keys;
    @Autowired
    private ApiKeyRepository repository;
    @Autowired
    private AuditService audit;

    private String datasourceId;

    @BeforeAll
    void findDatasource() throws Exception {
        datasourceId = json.readTree(get("/api/v1/datasources", ADMIN, ADMIN_PASSWORD).body()).get(0).get("id").asString();
    }

    @Test
    void feedKeyReadsDataAsBearerAndAsBasicPassword() throws Exception {
        String secret = feedKey(serviceUser(true));

        assertThat(feed("/odata/Sales?$top=1", secret).statusCode()).isEqualTo(200);
        assertThat(get("/odata/Sales?$top=1", "apikey", secret).statusCode()).isEqualTo(200);
    }

    @Test
    void requestsAreRecordedUnderTheKeyName() throws Exception {
        String name = "named-" + suffix();
        String secret = keys.create(name, serviceUser(true), null, 30).secret();

        feed("/odata/Sales?$top=1", secret);

        JsonNode executions = json.readTree(get("/api/v1/stats/executions?caller=apikey:" + name, ADMIN, ADMIN_PASSWORD).body());
        assertThat(executions.toString()).contains("apikey:" + name);
        assertThat(keys.findAll()).filteredOn(k -> k.name().equals(name)).singleElement()
                .satisfies(k -> assertThat(k.lastUsedAt()).isNotNull());
    }

    @Test
    void keyNeverBecomesADatabaseLogin() throws Exception {
        String secret = feedKey(serviceUser(false));

        assertThat(feed("/odata/Sales?$top=1", secret).statusCode()).isEqualTo(401);
    }

    @Test
    void revokedKeyStopsAtOnce() throws Exception {
        ApiKeyService.Created key = keys.create("revoked-" + suffix(), serviceUser(true), null, 30);
        assertThat(feed("/odata/Sales?$top=1", key.secret()).statusCode()).isEqualTo(200);

        assertThat(send("DELETE", "/api/v1/api-keys/" + key.key().id(), null, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(204);

        assertThat(feed("/odata/Sales?$top=1", key.secret()).statusCode()).isEqualTo(401);
    }

    @Test
    void expiredKeyIsRefused() throws Exception {
        ApiKeyService.Created key = keys.create("expired-" + suffix(), serviceUser(true), null, 30);
        ApiKeyEntity entity = repository.findById(key.key().id()).orElseThrow();
        entity.setExpiresAt(Instant.now().minusSeconds(1));
        repository.save(entity);

        assertThat(feed("/odata/Sales?$top=1", key.secret()).statusCode()).isEqualTo(401);
    }

    @Test
    void unknownKeyIsRefusedAndAudited() throws Exception {
        assertThat(feed("/odata/", "jdo_unknown").statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/v1/datasources", null, null, null, "Authorization", "Bearer jdo_unknown").statusCode()).isEqualTo(401);

        assertThat(audit.latest(20)).filteredOn(e -> "apikey".equals(e.adminUser()))
                .extracting(e -> e.action() + " " + e.entityType()).contains("LOGIN_FAILED FEED_LOGIN", "LOGIN_FAILED ADMIN_LOGIN");
    }

    @Test
    void disabledServiceUserTakesItsKeysOffline() throws Exception {
        UUID user = serviceUser(true);
        String secret = feedKey(user);
        assertThat(send("PUT", "/api/v1/users/" + user, "{\"username\":\"ignored\",\"enabled\":false}", ADMIN, ADMIN_PASSWORD).statusCode())
                .isEqualTo(200);

        assertThat(feed("/odata/Sales?$top=1", secret).statusCode()).isEqualTo(401);
    }

    @Test
    void forcePassthroughUserCannotHaveAKey() throws Exception {
        HttpResponse<String> user = send("POST", "/api/v1/users",
                "{\"username\":\"svc_" + suffix() + "\",\"password\":\"long-enough-pw\",\"forcePassthrough\":true}", ADMIN, ADMIN_PASSWORD);
        UUID id = UUID.fromString(json.readTree(user.body()).get("id").asString());

        Assertions.assertThatThrownBy(() -> keys.create("pass-" + suffix(), id, null, 30)).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void keysStayOnTheirSide() throws Exception {
        String feedKey = feedKey(serviceUser(true));
        String apiKey = keys.create("admin-" + suffix(), null, AdminRole.ADMIN, 30).secret();

        assertThat(feed("/api/v1/datasources", feedKey).statusCode()).isEqualTo(401);
        assertThat(feed("/odata/", apiKey).statusCode()).isEqualTo(401);
        assertThat(feed("/admin", apiKey).statusCode()).isNotEqualTo(200);
    }

    @Test
    void restRolesApply() throws Exception {
        String viewer = keys.create("viewer-" + suffix(), null, AdminRole.VIEWER, 30).secret();
        String admin = keys.create("writer-" + suffix(), null, AdminRole.ADMIN, 30).secret();
        String group = "{\"name\":\"grp-" + suffix() + "\"}";

        assertThat(feed("/api/v1/datasources", viewer).statusCode()).isEqualTo(200);
        assertThat(sendKey("POST", "/api/v1/groups", group, viewer).statusCode()).isEqualTo(403);
        assertThat(sendKey("POST", "/api/v1/groups", group, admin).statusCode()).isIn(200, 201);
    }

    @Test
    void changesAreAuditedUnderTheKey() throws Exception {
        String name = "audited-" + suffix();
        String secret = keys.create(name, null, AdminRole.ADMIN, 30).secret();

        sendKey("POST", "/api/v1/groups", "{\"name\":\"grp-" + suffix() + "\"}", secret);

        assertThat(audit.latest(20)).anyMatch(e -> ("apikey:" + name).equals(e.adminUser()));
    }

    @Test
    void aKeyCannotMintOrRevokeKeys() throws Exception {
        String admin = keys.create("minter-" + suffix(), null, AdminRole.ADMIN, 30).secret();
        ApiKey other = keys.create("other-" + suffix(), null, AdminRole.VIEWER, 30).key();
        String body = "{\"name\":\"minted-" + suffix() + "\",\"adminRole\":\"ADMIN\",\"expiresInDays\":3650}";

        assertThat(sendKey("POST", "/api/v1/api-keys", body, admin).statusCode()).isEqualTo(403);
        assertThat(sendKey("DELETE", "/api/v1/api-keys/" + other.id(), null, admin).statusCode()).isEqualTo(403);
    }

    @Test
    void secretAppearsOnlyAtCreation() throws Exception {
        String name = "once-" + suffix();
        HttpResponse<String> created = send("POST", "/api/v1/api-keys",
                "{\"name\":\"" + name + "\",\"adminRole\":\"VIEWER\",\"expiresInDays\":30}", ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).isEqualTo(201);
        String secret = json.readTree(created.body()).get("secret").asString();
        assertThat(secret).startsWith("jdo_").hasSizeGreaterThan(40);

        String list = get("/api/v1/api-keys", ADMIN, ADMIN_PASSWORD).body();
        assertThat(list).contains(name).doesNotContain(secret).doesNotContain("keyHash");
        assertThat(audit.latest(20).toString()).doesNotContain(secret);
    }

    @Test
    void invalidInputIsRefused() throws Exception {
        String[] bodies = {
                "{\"name\":\"a\",\"adminRole\":\"ADMIN\",\"expiresInDays\":30}",
                "{\"name\":\"no-target\",\"expiresInDays\":30}",
                "{\"name\":\"no-expiry\",\"adminRole\":\"ADMIN\"}",
                "{\"name\":\"too-long\",\"adminRole\":\"ADMIN\",\"expiresInDays\":3651}",
        };
        for (String body : bodies) {
            assertThat(send("POST", "/api/v1/api-keys", body, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);
        }
        String both = "{\"name\":\"both\",\"adminRole\":\"ADMIN\",\"serviceUserId\":\"" + UUID.randomUUID() + "\",\"expiresInDays\":30}";
        assertThat(send("POST", "/api/v1/api-keys", both, ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(400);
    }

    @Test
    void headerParsing() {
        assertThat(ApiKeyService.keyIn("Bearer jdo_x")).contains("jdo_x");
        assertThat(ApiKeyService.keyIn("bearer  jdo_x ")).contains("jdo_x");
        assertThat(ApiKeyService.keyIn("Bearer some.jwt.token")).isEmpty();
        assertThat(ApiKeyService.keyIn("Basic " + basic("apikey:jdo_x"))).contains("jdo_x");
        assertThat(ApiKeyService.keyIn("Basic " + basic("anna:jdo_x"))).isEmpty();
        assertThat(ApiKeyService.keyIn("Basic " + basic("apikey:password"))).isEmpty();
        assertThat(ApiKeyService.keyIn(null)).isEmpty();
    }

    private static String basic(String value) {
        return java.util.Base64.getEncoder().encodeToString(value.getBytes());
    }

    private UUID serviceUser(boolean withCredentials) throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/users",
                "{\"username\":\"svc_" + suffix() + "\",\"password\":\"long-enough-pw\"}", ADMIN, ADMIN_PASSWORD);
        assertThat(created.statusCode()).isIn(200, 201);
        String id = json.readTree(created.body()).get("id").asString();
        if (withCredentials) {
            assertThat(send("PUT", "/api/v1/users/" + id + "/credentials/" + datasourceId,
                    "{\"dbUsername\":\"demo\",\"dbPassword\":\"demo\"}", ADMIN, ADMIN_PASSWORD).statusCode()).isLessThan(300);
        }
        return UUID.fromString(id);
    }

    private String feedKey(UUID user) {
        return keys.create("feed-" + suffix(), user, null, 30).secret();
    }

    private HttpResponse<String> feed(String path, String secret) throws Exception {
        return sendKey("GET", path, null, secret);
    }

    private HttpResponse<String> sendKey(String method, String path, String body, String secret) throws Exception {
        return send(method, path, body, null, null, "Authorization", "Bearer " + secret);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
