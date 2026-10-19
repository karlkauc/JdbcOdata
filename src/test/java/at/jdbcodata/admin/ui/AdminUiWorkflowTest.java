package at.jdbcodata.admin.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.health.DatasourceHealthService;
import at.jdbcodata.identity.FixedCredential;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.query.PrincipalType;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Dashboard, navigation, search, list filters, combined save of the query editor and the datasource health check. */
class AdminUiWorkflowTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    DatasourceService datasourceService;
    @Autowired
    QueryService queryService;
    @Autowired
    FixedCredentialService fixedCredentials;
    @Autowired
    DatasourceHealthService health;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private String page(String path) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(admin())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void dashboardShowsKeyFiguresAttentionAndNavigation() throws Exception {
        String dashboard = page("/admin");
        assertThat(dashboard).contains("Executions · 24 h").contains("Error rate").contains("Cache hit rate")
                .contains("Duration P50 / P95").contains("Needs attention").contains("Connect from Excel / Power BI")
                .contains("<polyline");
        // sidebar counts, service URL, breadcrumb and user menu are part of every page
        assertThat(dashboard).contains("class=\"nav-count\"").contains("id=\"nav-service-root\"").contains("crumb-current")
                .contains("global-search").contains("Sign out");
        assertThat(page("/admin/queries")).contains("<span class=\"crumb-section\">Data</span>");
    }

    @Test
    void searchReturnsFragmentForHtmxAndPageOtherwise() throws Exception {
        String fragment = mvc.perform(MockMvcRequestBuilders.get("/admin/search").param("q", "sal").header("HX-Request", "true").with(admin()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(fragment).contains("search-hit").contains("Sales").doesNotContain("<html").doesNotContain("sidebar");
        assertThat(mvc.perform(MockMvcRequestBuilders.get("/admin/search").param("q", "statis").header("HX-Request", "true").with(admin()))
                .andReturn().getResponse().getContentAsString()).contains("/admin/stats");
        assertThat(page("/admin/search?q=zzz_nothing")).contains("No results").contains("sidebar");
        assertThat(page("/admin/search")).contains("Enter a search term");
    }

    @Test
    void queryListFiltersAndPages() throws Exception {
        assertThat(page("/admin/queries?q=sales")).contains("row-title").contains("Sales");
        assertThat(page("/admin/queries?q=zzz_nothing")).contains("No query matches the filters");
        assertThat(page("/admin/queries?ds=demo&mode=CALLER&status=enabled&snapshot=present")).contains(">Sales<");
        assertThat(page("/admin/queries?mode=FIXED&q=Sales")).doesNotContain(">Sales<");
        assertThat(page("/admin/queries?usage=unused&q=zzz")).contains("Reset filters");
        assertThat(page("/admin/queries?page=99")).contains("Queries");
    }

    @Test
    void editorSavesDefinitionColumnsAndGrantsTogether() throws Exception {
        String demoId = datasourceService.findByName("demo").orElseThrow().id().toString();
        String name = "Wf_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mvc.perform(post("/admin/queries").with(admin()).with(csrf())
                        .param("name", name).param("datasourceId", demoId).param("sql", "SELECT customer, amount FROM sales")
                        .param("executionMode", "CALLER").param("keyMode", "ROWID").param("enabled", "true").param("cacheTtlSec", "60"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String location = created.getResponse().getRedirectedUrl();
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
        mvc.perform(post("/admin/queries/" + id + "/snapshot").with(admin()).with(csrf()).param("dbUser", "demo").param("dbPassword", "demo"))
                .andExpect(status().isOk());

        String editor = page("/admin/queries/" + id);
        assertThat(editor).contains("data-tab=\"spalten\"").contains("data-savebar").contains("name=\"columnsPresent\"")
                .contains("name=\"grantsPresent\"").contains("form=\"query-form\"").doesNotContain("Save column settings");

        // one save: new TTL, customer becomes key and amount hidden, grant for all authenticated callers
        mvc.perform(post("/admin/queries/" + id).with(admin()).with(csrf())
                        .param("name", name).param("datasourceId", demoId).param("sql", "SELECT customer, amount FROM sales")
                        .param("executionMode", "CALLER").param("keyMode", "COLUMNS").param("enabled", "true").param("cacheTtlSec", "120")
                        .param("columnsPresent", "1").param("key_customer", "on").param("visible_customer", "on")
                        .param("grantsPresent", "1").param("all", "on"))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("message"));
        QueryDefinition q = queryService.require(id);
        assertThat(q.cacheTtlSec()).isEqualTo(120);
        assertThat(q.version()).isEqualTo(1);
        assertThat(q.columns().get(0).key()).isTrue();
        assertThat(q.columns().get(1).visible()).isFalse();
        assertThat(queryService.grants(id)).anyMatch(g -> g.principalType() == PrincipalType.ALL_AUTHENTICATED);

        // a changed SQL drops the snapshot: column fields of the old snapshot are ignored, grants still apply
        mvc.perform(post("/admin/queries/" + id).with(admin()).with(csrf())
                        .param("name", name).param("datasourceId", demoId).param("sql", "SELECT customer FROM sales")
                        .param("executionMode", "CALLER").param("keyMode", "ROWID").param("enabled", "true").param("cacheTtlSec", "120")
                        .param("columnsPresent", "1").param("visible_customer", "on").param("grantsPresent", "1"))
                .andExpect(flash().attributeExists("message"));
        q = queryService.require(id);
        assertThat(q.version()).isEqualTo(2);
        assertThat(q.hasSnapshot()).isFalse();
        assertThat(queryService.grants(id)).isEmpty();
        queryService.delete(id);
    }

    @Test
    void healthCheckUsesTechnicalAccountOrSendsToManualTest() throws Exception {
        UUID demoId = datasourceService.findByName("demo").orElseThrow().id();
        boolean hadAccount = fixedCredentials.findAll().stream().anyMatch(c -> c.datasourceId().equals(demoId));
        if (!hadAccount) {
            mvc.perform(post("/admin/datasources/" + demoId + "/health").with(admin()).with(csrf()))
                    .andExpect(redirectedUrl("/admin/datasources/" + demoId)).andExpect(flash().attributeExists("error"));
        }
        FixedCredential account = fixedCredentials.create(new FixedCredentialService.FixedCredentialSpec(
                "health_" + UUID.randomUUID().toString().substring(0, 8), null, demoId, "demo", "demo"));
        try {
            mvc.perform(post("/admin/datasources/" + demoId + "/health").with(admin()).with(csrf()))
                    .andExpect(redirectedUrl("/admin")).andExpect(flash().attributeExists("message"));
            DatasourceHealthService.Health h = health.all().stream().filter(x -> x.datasourceId().equals(demoId)).findFirst().orElseThrow();
            assertThat(h.state()).isEqualTo(DatasourceHealthService.State.OK);
            assertThat(h.active()).isTrue();
            assertThat(h.checkedAt()).isBeforeOrEqualTo(Instant.now());
            assertThat(page("/admin/datasources")).contains("Reachability").contains("status-dot ok");
        } finally {
            fixedCredentials.delete(account.id());
        }
        health.check(demoId);
        assertThat(health.all()).anyMatch(x -> x.datasourceId().equals(demoId));
    }

    @Test
    void formatsRelativeTimes() {
        Instant now = Instant.parse("2026-10-19T12:00:00Z");
        assertThat(UiFormat.ago(now.minusSeconds(10), now)).isEqualTo("just now");
        assertThat(UiFormat.ago(now.minusSeconds(12 * 60), now)).isEqualTo("12 min ago");
        assertThat(UiFormat.ago(now.minusSeconds(3 * 3600), now)).isEqualTo("3 h ago");
        assertThat(UiFormat.ago(now.minusSeconds(26 * 3600), now)).isEqualTo("yesterday");
        assertThat(UiFormat.ago(now.minusSeconds(5 * 86_400), now)).isEqualTo("5 days ago");
        assertThat(UiFormat.ago(null, now)).isEqualTo("–");
        UiFormat fmt = new UiFormat();
        assertThat(fmt.spark(java.util.List.of(0, 5, 10), 100, 20)).isEqualTo("0.0,18.0 50.0,10.0 100.0,2.0");
        assertThat(fmt.bars(java.util.List.of(0L, 5L, 10L), 20)).containsExactly(2, 10, 20);
        assertThat(fmt.pct(0.634, 0)).isEqualTo("63%");
        assertThat(fmt.num(12345)).isEqualTo("12,345");
    }
}
