package at.jdbcodata.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import at.jdbcodata.common.ConflictException;
import at.jdbcodata.common.InvalidRequestException;
import at.jdbcodata.common.NotFoundException;
import at.jdbcodata.datasource.Datasource;
import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.query.ExecutionMode;
import at.jdbcodata.query.PrincipalType;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.query.QuerySpec;
import at.jdbcodata.query.SchemaSnapshotService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** End-to-end identity scenarios from ARCHITECTURE.md §5 over the real OData endpoint. */
class IdentityFlowTest extends DemoProfileTest {

    @Autowired ServiceUserService users;
    @Autowired FixedCredentialService fixed;
    @Autowired QueryService queries;
    @Autowired DatasourceService datasources;
    @Autowired SchemaSnapshotService snapshots;
    @Autowired ExecutionIdentityResolver resolver;

    private final JsonMapper json = JsonMapper.builder().build();

    private static String unique(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');
    }

    @Test
    void serviceUserWithStoredCredentialsAndPassThrough() throws Exception {
        Datasource demo = datasources.findByName("demo").orElseThrow();
        String name = unique("svc_");
        ServiceUser user = users.create(new ServiceUserService.ServiceUserSpec(name, "Maria", "maria-pass", false, true, List.of()));
        assertThat(user.username()).isEqualTo(name);

        // wrong service password → 401 from the service, never forwarded to the DB
        HttpResponse<String> wrong = get("/odata/Sales?$top=1", name, "nope");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(wrong.body()).contains("credentials were rejected").doesNotContain(name);

        // no stored credentials yet → pass-through with the service password fails at the DB
        assertThat(get("/odata/Sales?$top=1", name, "maria-pass").statusCode()).isEqualTo(401);

        users.storeCredential(user.id(), demo.id(), "demo", "demo");
        assertThat(users.credentialsOf(user.id())).hasSize(1);
        assertThat(users.credentialsOf(user.id()).get(0).datasourceName()).isEqualTo("demo");
        HttpResponse<String> ok = get("/odata/Sales?$top=1", name, "maria-pass");
        assertThat(ok.statusCode()).as(ok.body()).isEqualTo(200);

        // the execution log shows caller and effective DB user separately
        HttpResponse<String> execs = get("/api/v1/stats/executions?caller=" + name + "&limit=5", ADMIN, ADMIN_PASSWORD);
        JsonNode first = json.readTree(execs.body()).get(0);
        assertThat(first.get("caller").asString()).isEqualTo(name);
        assertThat(first.get("effectiveDbUser").asString()).isEqualTo("demo");
        assertThat(first.get("executionMode").asString()).isEqualTo("CALLER");

        // force pass-through ignores the stored credentials
        users.update(user.id(), new ServiceUserService.ServiceUserSpec(name, "Maria", null, true, true, List.of()));
        assertThat(get("/odata/Sales?$top=1", name, "maria-pass").statusCode()).isEqualTo(401);

        // disabled user cannot log in
        users.update(user.id(), new ServiceUserService.ServiceUserSpec(name, "Maria", null, false, false, List.of()));
        assertThat(get("/odata/Sales?$top=1", name, "maria-pass").statusCode()).isEqualTo(401);

        users.removeCredential(user.id(), demo.id());
        assertThatThrownBy(() -> users.removeCredential(user.id(), demo.id())).isInstanceOf(NotFoundException.class);
        users.delete(user.id());
        assertThat(users.findByUsername(name)).isEmpty();
    }

    @Test
    void fixedQueriesNeedGrantsAndRunUnderTechnicalAccount() throws Exception {
        Datasource demo = datasources.findByName("demo").orElseThrow();
        FixedCredential account = fixed.create(new FixedCredentialService.FixedCredentialSpec(unique("tech_"), "demo account", demo.id(), "demo", "demo"));
        assertThat(fixed.resolveLogin(account.id()).password()).isEqualTo("demo");
        assertThat(fixed.resolveLogin(account.id()).toString()).doesNotContain("demo\"");

        String qName = unique("Fixed_");
        QueryDefinition q = queries.create(new QuerySpec(qName, null, demo.id(), "SELECT id, customer FROM sales", ExecutionMode.FIXED,
                account.id(), false, 0, null, null, null, true, List.of()));
        assertThat(q.fixedCredentialName()).isEqualTo(account.name());
        snapshots.snapshot(q, new ExecutionIdentity("admin", "demo", "demo", ExecutionMode.CALLER), Map.of());

        String svc = unique("svc_");
        ServiceUser user = users.create(new ServiceUserService.ServiceUserSpec(svc, null, "secret-pw", false, true, List.of()));

        // no grant: not in the service document, 404 on access
        assertThat(get("/odata/", svc, "secret-pw").body()).doesNotContain("\"name\":\"" + qName + "\"");
        assertThat(get("/odata/" + qName, svc, "secret-pw").statusCode()).isEqualTo(404);
        assertThat(get("/odata/" + qName, "demo", "demo").statusCode()).isEqualTo(404);

        // user grant
        queries.setGrants(q.id(), List.of(new QueryService.GrantSpec(PrincipalType.USER, user.id())));
        assertThat(get("/odata/", svc, "secret-pw").body()).contains("\"name\":\"" + qName + "\"");
        assertThat(get("/odata/$metadata", svc, "secret-pw").body()).contains("EntitySet Name=\"" + qName + "\"");
        HttpResponse<String> r = get("/odata/" + qName + "?$top=1", svc, "secret-pw");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode exec = json.readTree(get("/api/v1/stats/executions?queryName=" + qName + "&limit=1", ADMIN, ADMIN_PASSWORD).body()).get(0);
        assertThat(exec.get("effectiveDbUser").asString()).isEqualTo("demo");
        assertThat(exec.get("executionMode").asString()).isEqualTo("FIXED");
        assertThat(exec.get("caller").asString()).isEqualTo(svc);
        assertThat(get("/odata/" + qName, "demo", "demo").statusCode()).as("pass-through caller still not granted").isEqualTo(404);

        // group grant
        ServiceGroup group = users.createGroup(unique("grp_"), null);
        String svc2 = unique("svc_");
        ServiceUser member = users.create(new ServiceUserService.ServiceUserSpec(svc2, null, "member-pw", false, true, List.of(group.id())));
        assertThat(member.inGroup(group.id())).isTrue();
        queries.setGrants(q.id(), List.of(new QueryService.GrantSpec(PrincipalType.GROUP, group.id())));
        assertThat(get("/odata/" + qName + "?$top=1", svc2, "member-pw").statusCode()).isEqualTo(200);
        assertThat(get("/odata/" + qName + "?$top=1", svc, "secret-pw").statusCode()).isEqualTo(404);
        assertThat(queries.grants(q.id()).get(0).principalName()).isEqualTo(group.name());

        // all authenticated: pass-through callers must prove a DB login against the datasource
        queries.setGrants(q.id(), List.of(new QueryService.GrantSpec(PrincipalType.ALL_AUTHENTICATED, null)));
        assertThat(get("/odata/" + qName + "?$top=1", "demo", "demo").statusCode()).isEqualTo(200);
        assertThat(get("/odata/" + qName + "?$top=1", "demo", "wrong").statusCode()).isEqualTo(401);
        HttpResponse<String> unverified = get("/odata/", "demo", "wrong");
        assertThat(unverified.statusCode()).as("no service document without a valid DB login").isEqualTo(401);
        assertThat(unverified.body()).doesNotContain(qName);

        // validation of FIXED definitions
        assertThatThrownBy(() -> queries.create(new QuerySpec(unique("Bad_"), null, demo.id(), "SELECT 1", ExecutionMode.FIXED,
                null, null, null, null, null, null, null, List.of()))).isInstanceOf(InvalidRequestException.class).hasMessageContaining("technical account");
        assertThatThrownBy(() -> queries.create(new QuerySpec(unique("Bad_"), null, demo.id(), "SELECT 1", ExecutionMode.FIXED,
                UUID.randomUUID(), null, null, null, null, null, null, List.of()))).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> queries.setGrants(q.id(), List.of(new QueryService.GrantSpec(PrincipalType.USER, UUID.randomUUID()))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> queries.setGrants(q.id(), List.of(new QueryService.GrantSpec(null, null))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> fixed.delete(account.id())).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> datasources.delete(demo.id())).isInstanceOf(ConflictException.class);

        // technical account validation and update
        assertThatThrownBy(() -> fixed.create(new FixedCredentialService.FixedCredentialSpec(account.name(), null, demo.id(), "x", "y")))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> fixed.create(new FixedCredentialService.FixedCredentialSpec("!", null, demo.id(), "x", "y")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> fixed.create(new FixedCredentialService.FixedCredentialSpec(unique("t_"), null, demo.id(), "", "y")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> fixed.create(new FixedCredentialService.FixedCredentialSpec(unique("t_"), null, null, "x", "y")))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> fixed.create(new FixedCredentialService.FixedCredentialSpec(unique("t_"), null, demo.id(), "x", null)))
                .isInstanceOf(InvalidRequestException.class);
        FixedCredential updated = fixed.update(account.id(), new FixedCredentialService.FixedCredentialSpec(account.name(), "changed", demo.id(), "demo", ""));
        assertThat(updated.description()).isEqualTo("changed");
        assertThat(fixed.resolveLogin(account.id()).password()).as("empty password keeps the old one").isEqualTo("demo");
        assertThat(fixed.findAll()).extracting(FixedCredential::name).contains(account.name());

        queries.delete(q.id());
        fixed.delete(account.id());
        assertThatThrownBy(() -> fixed.require(account.id())).isInstanceOf(NotFoundException.class);
        users.delete(user.id());
        users.delete(member.id());
        users.deleteGroup(group.id());
        assertThatThrownBy(() -> users.deleteGroup(group.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void serviceUserValidation() {
        assertThatThrownBy(() -> users.create(new ServiceUserService.ServiceUserSpec("x", null, "longenough", false, true, List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("Username");
        assertThatThrownBy(() -> users.create(new ServiceUserService.ServiceUserSpec("svc_short", null, "short", false, true, List.of())))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("8 characters");
        String name = unique("svc_");
        ServiceUser u = users.create(new ServiceUserService.ServiceUserSpec(name, null, "longenough", null, null, null));
        assertThatThrownBy(() -> users.create(new ServiceUserService.ServiceUserSpec(name.toUpperCase(), null, "longenough", false, true, List.of())))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> users.update(u.id(), new ServiceUserService.ServiceUserSpec(name, null, "short", false, true, List.of())))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> users.update(u.id(), new ServiceUserService.ServiceUserSpec(name, null, null, false, true, List.of(UUID.randomUUID()))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> users.storeCredential(u.id(), datasources.findByName("demo").orElseThrow().id(), "", "x"))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> users.createGroup("!", null)).isInstanceOf(InvalidRequestException.class);
        ServiceGroup g = users.createGroup(unique("grp_"), "d");
        assertThatThrownBy(() -> users.createGroup(g.name(), null)).isInstanceOf(ConflictException.class);
        assertThat(users.authenticate("no_such_user_" + name, "x")).isEmpty();
        assertThat(users.authenticate(name, "longenough")).isPresent();
        assertThatThrownBy(() -> users.authenticate(name, "wrong")).isInstanceOf(ServiceAuthenticationException.class);
        assertThat(users.groupIdsOf(users.require(u.id()))).isEmpty();
        users.deleteGroup(g.id());
        users.delete(u.id());
        assertThatThrownBy(() -> users.require(u.id())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void cachePerUserWithRefreshAndInvalidation() throws Exception {
        Datasource demo = datasources.findByName("demo").orElseThrow();
        String qName = unique("Cached_");
        QueryDefinition q = queries.create(new QuerySpec(qName, null, demo.id(), "SELECT id FROM sales WHERE sale_year = :y", ExecutionMode.CALLER,
                null, true, 120, null, null, null, true, List.of(new at.jdbcodata.query.QueryParameter("y", at.jdbcodata.query.ParameterType.INT, false, "2025", null, false))));
        snapshots.snapshot(q, new ExecutionIdentity("demo", "demo", "demo", ExecutionMode.CALLER), Map.of());

        HttpResponse<String> miss = get("/odata/" + qName + "?$top=1", "demo", "demo");
        assertThat(miss.statusCode()).isEqualTo(200);
        assertThat(miss.headers().firstValue("X-Cache")).hasValue("MISS");
        HttpResponse<String> hit = get("/odata/" + qName + "?$top=1", "demo", "demo");
        assertThat(hit.headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(hit.headers().firstValue("X-Cache-Age")).isPresent();
        // a cache hit must not bypass the DB login: same user name, wrong password
        HttpResponse<String> wrongPassword = get("/odata/" + qName + "?$top=1", "demo", "wrong");
        assertThat(wrongPassword.statusCode()).as("cache hit with wrong password").isEqualTo(401);
        assertThat(get("/odata/" + qName + "?y=2024", "demo", "demo").headers().firstValue("X-Cache")).as("other params").hasValue("MISS");
        assertThat(get("/odata/" + qName + "/$count", "demo", "demo").headers().firstValue("X-Cache")).hasValue("HIT");
        assertThat(get("/odata/" + qName + "?refresh=true", "demo", "demo").headers().firstValue("X-Cache")).hasValue("BYPASS");
        assertThat(get("/odata/" + qName, "demo", "demo").headers().firstValue("X-Cache")).hasValue("HIT");

        HttpResponse<String> cacheApi = get("/api/v1/cache", ADMIN, ADMIN_PASSWORD);
        assertThat(cacheApi.body()).contains(qName);
        HttpResponse<String> invalidated = send("DELETE", "/api/v1/cache?queryId=" + q.id() + "&dbUser=demo", null, ADMIN, ADMIN_PASSWORD);
        assertThat(invalidated.statusCode()).isEqualTo(200);
        assertThat(json.readTree(invalidated.body()).get("invalidated").asInt()).isGreaterThanOrEqualTo(1);
        assertThat(get("/odata/" + qName, "demo", "demo").headers().firstValue("X-Cache")).hasValue("MISS");

        // a new version (SQL change) uses new keys
        queries.update(q.id(), new QuerySpec(qName, null, demo.id(), "SELECT id, customer FROM sales WHERE sale_year = :y", ExecutionMode.CALLER,
                null, true, 120, null, null, null, true, q.parameters()));
        QueryDefinition v2 = queries.require(q.id());
        snapshots.snapshot(v2, new ExecutionIdentity("demo", "demo", "demo", ExecutionMode.CALLER), Map.of());
        assertThat(get("/odata/" + qName, "demo", "demo").headers().firstValue("X-Cache")).hasValue("MISS");

        HttpResponse<String> all = send("DELETE", "/api/v1/cache", null, ADMIN, ADMIN_PASSWORD);
        assertThat(all.statusCode()).isEqualTo(200);
        HttpResponse<String> summary = get("/api/v1/stats/summary", ADMIN, ADMIN_PASSWORD);
        assertThat(summary.statusCode()).isEqualTo(200);
        assertThat(json.readTree(summary.body()).get("summary").get("executions").asLong()).isGreaterThan(0);
        assertThat(get("/api/v1/stats/export.csv?queryName=" + qName, ADMIN, ADMIN_PASSWORD).body()).contains(qName).contains("HIT");
        assertThat(get("/api/v1/audit?entityType=QUERY&entityId=" + q.id(), ADMIN, ADMIN_PASSWORD).body()).contains("INVALIDATE_CACHE").contains("CREATE");
        queries.delete(q.id());
    }

    @Test
    void resolverHandlesMissingTechnicalAccount() {
        Datasource demo = datasources.findByName("demo").orElseThrow();
        QueryDefinition broken = new QueryDefinition(UUID.randomUUID(), "Broken", null, demo.id(), "demo", "SELECT 1", ExecutionMode.FIXED,
                null, null, false, 0, null, null, at.jdbcodata.query.KeyMode.ROWID, 1, true, List.of(), List.of(), null, null);
        IdentityContext ctx = resolver.authenticate(new BasicCredentials("demo", "demo"));
        assertThat(ctx.isServiceUser()).isFalse();
        assertThat(ctx.caller()).isEqualTo("demo");
        assertThatThrownBy(() -> resolver.resolve(broken, ctx)).isInstanceOf(QueryNotVisibleException.class);
    }
}
