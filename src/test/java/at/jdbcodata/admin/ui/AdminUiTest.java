package at.jdbcodata.admin.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.query.QueryDefinition;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class AdminUiTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    DatasourceService datasourceService;
    @Autowired
    QueryService queryService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    @Test
    void loginAndNavigation() throws Exception {
        assertThat(body(mvc.perform(MockMvcRequestBuilders.get("/login")).andExpect(status().isOk()).andReturn())).contains("Anmelden");
        mvc.perform(MockMvcRequestBuilders.get("/admin").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", endsWith("/login")));
        mvc.perform(MockMvcRequestBuilders.get("/").accept(MediaType.TEXT_HTML)).andExpect(status().is3xxRedirection()).andExpect(header().string("Location", endsWith("/login")));
        mvc.perform(MockMvcRequestBuilders.get("/admin").accept(MediaType.APPLICATION_JSON)).andExpect(status().isUnauthorized());
        mvc.perform(MockMvcRequestBuilders.get("/").with(admin())).andExpect(redirectedUrl("/admin"));
        String dashboard = body(mvc.perform(MockMvcRequestBuilders.get("/admin").with(admin())).andExpect(status().isOk()).andReturn());
        assertThat(dashboard).contains("Übersicht").contains("Sales").contains("admin");
        mvc.perform(MockMvcRequestBuilders.get("/webjars/htmx.org/2.0.11/dist/htmx.min.js")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/css/admin.css")).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/js/admin.js")).andExpect(status().isOk());
    }

    @Test
    void datasourcePages() throws Exception {
        assertThat(body(mvc.perform(MockMvcRequestBuilders.get("/admin/datasources").with(admin())).andExpect(status().isOk()).andReturn())).contains("demo");
        assertThat(body(mvc.perform(MockMvcRequestBuilders.get("/admin/datasources/new").with(admin())).andExpect(status().isOk()).andReturn())).contains("Neue Datenquelle");

        String invalid = body(mvc.perform(post("/admin/datasources").with(admin()).with(csrf())
                .param("name", "").param("jdbcUrl", "")).andExpect(status().isOk()).andReturn());
        assertThat(invalid).contains("field-error");

        String name = "ui_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mvc.perform(post("/admin/datasources").with(admin()).with(csrf())
                        .param("name", name).param("jdbcUrl", "jdbc:h2:mem:ui;DB_CLOSE_DELAY=-1")
                        .param("defaultTimeoutSec", "5").param("maxRows", "10").param("maxConcurrent", "2").param("enabled", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("message")).andReturn();
        String location = created.getResponse().getRedirectedUrl();
        String id = location.substring(location.lastIndexOf('/') + 1);

        String duplicate = body(mvc.perform(post("/admin/datasources").with(admin()).with(csrf())
                .param("name", name).param("jdbcUrl", "jdbc:h2:mem:ui2")).andExpect(status().isOk()).andReturn());
        assertThat(duplicate).contains("already exists");

        String edit = body(mvc.perform(MockMvcRequestBuilders.get("/admin/datasources/" + id).with(admin())).andExpect(status().isOk()).andReturn());
        assertThat(edit).contains("jdbc:h2:mem:ui").contains("Verbindung testen");

        mvc.perform(post("/admin/datasources/" + id).with(admin()).with(csrf())
                        .param("name", name).param("jdbcUrl", "jdbc:h2:mem:ui;DB_CLOSE_DELAY=-1").param("description", "edited")
                        .param("defaultTimeoutSec", "5").param("maxRows", "10").param("maxConcurrent", "2"))
                .andExpect(redirectedUrl("/admin/datasources/" + id));
        assertThat(datasourceService.require(UUID.fromString(id)).description()).isEqualTo("edited");

        mvc.perform(post("/admin/datasources/" + id).with(admin()).with(csrf()).param("name", "").param("jdbcUrl", "x"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/datasources/" + id).with(admin()).with(csrf()).param("name", "demo").param("jdbcUrl", "x"))
                .andExpect(status().isOk());

        String ok = body(mvc.perform(post("/admin/datasources/" + id + "/test").with(admin()).with(csrf())
                .param("dbUser", "sa").param("dbPassword", "")).andExpect(status().isOk()).andReturn());
        assertThat(ok).contains("Verbindung erfolgreich").contains("H2");
        String failed = body(mvc.perform(post("/admin/datasources/" + id + "/test").with(admin()).with(csrf())
                .param("dbUser", "sa").param("dbPassword", "wrong")).andExpect(status().isOk()).andReturn());
        assertThat(failed).contains("Verbindung fehlgeschlagen");

        String demoId = datasourceService.findByName("demo").orElseThrow().id().toString();
        mvc.perform(post("/admin/datasources/" + demoId + "/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/datasources/" + demoId)).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/datasources/" + id + "/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/datasources"));
        assertThat(datasourceService.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    void queryEditorRoundTrip() throws Exception {
        String demoId = datasourceService.findByName("demo").orElseThrow().id().toString();
        assertThat(body(mvc.perform(MockMvcRequestBuilders.get("/admin/queries").with(admin())).andExpect(status().isOk()).andReturn())).contains("Sales");
        assertThat(body(mvc.perform(MockMvcRequestBuilders.get("/admin/queries/new").with(admin())).andExpect(status().isOk()).andReturn()))
                .contains("Neue Query").contains("param-template");

        String name = "Ui_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');
        String invalid = body(mvc.perform(post("/admin/queries").with(admin()).with(csrf())
                .param("name", "1bad").param("datasourceId", demoId).param("sql", "SELECT 1")).andExpect(status().isOk()).andReturn());
        assertThat(invalid).contains("field-error");
        String undeclared = body(mvc.perform(post("/admin/queries").with(admin()).with(csrf())
                .param("name", name).param("datasourceId", demoId).param("sql", "SELECT :nope")).andExpect(status().isOk()).andReturn());
        assertThat(undeclared).contains("undeclared parameter");

        MvcResult created = mvc.perform(post("/admin/queries").with(admin()).with(csrf())
                        .param("name", name).param("datasourceId", demoId)
                        .param("sql", "SELECT customer, amount FROM sales WHERE sale_year = :y")
                        .param("parameters[0].name", "y").param("parameters[0].type", "INT")
                        .param("parameters[0].required", "true").param("parameters[0].defaultValue", "2025")
                        .param("executionMode", "CALLER").param("keyMode", "ROWID").param("enabled", "true").param("cacheTtlSec", "3600"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("message")).andReturn();
        String location = created.getResponse().getRedirectedUrl();
        String id = location.substring(location.lastIndexOf('/') + 1);
        QueryDefinition q = queryService.require(UUID.fromString(id));
        assertThat(q.parameters()).hasSize(1);
        assertThat(q.parameters().get(0).required()).isTrue();

        String editor = body(mvc.perform(MockMvcRequestBuilders.get("/admin/queries/" + id).with(admin())).andExpect(status().isOk()).andReturn());
        assertThat(editor).contains("/odata/" + name).contains("Noch kein Schema-Snapshot").contains("Versionen")
                .contains("parameters[0].name").contains("tp_y");

        String preview = body(mvc.perform(post("/admin/queries/test").with(admin()).with(csrf())
                .param("name", name).param("datasourceId", demoId)
                .param("sql", "SELECT customer, amount FROM sales WHERE sale_year = :y")
                .param("parameters[0].name", "y").param("parameters[0].type", "INT")
                .param("dbUser", "demo").param("dbPassword", "demo").param("tp_y", "2024")).andExpect(status().isOk()).andReturn());
        assertThat(preview).contains("<span>12</span> Zeile(n)").contains("Alpha GmbH");
        String previewError = body(mvc.perform(post("/admin/queries/test").with(admin()).with(csrf())
                .param("name", name).param("datasourceId", demoId).param("sql", "SELECT nope FROM sales")
                .param("dbUser", "demo").param("dbPassword", "demo")).andExpect(status().isOk()).andReturn());
        assertThat(previewError).contains("flash err");
        String previewAuth = body(mvc.perform(post("/admin/queries/test").with(admin()).with(csrf())
                .param("name", name).param("datasourceId", demoId).param("sql", "SELECT 1")
                .param("dbUser", "demo").param("dbPassword", "wrong")).andExpect(status().isOk()).andReturn());
        assertThat(previewAuth).contains("rejected");

        String snapshotFailed = body(mvc.perform(post("/admin/queries/" + id + "/snapshot").with(admin()).with(csrf())
                .param("dbUser", "demo").param("dbPassword", "wrong")).andExpect(status().isOk()).andReturn());
        assertThat(snapshotFailed).contains("flash err");
        String snapshot = body(mvc.perform(post("/admin/queries/" + id + "/snapshot").with(admin()).with(csrf())
                .param("dbUser", "demo").param("dbPassword", "demo").param("tp_y", "2025")).andExpect(status().isOk()).andReturn());
        assertThat(snapshot).contains("Snapshot erstellt: 2 Spalten").contains("key_customer");

        mvc.perform(post("/admin/queries/" + id + "/columns").with(admin()).with(csrf())
                        .param("key_customer", "on").param("visible_customer", "on"))
                .andExpect(redirectedUrl("/admin/queries/" + id));
        q = queryService.require(UUID.fromString(id));
        assertThat(q.columns().get(0).key()).isTrue();
        assertThat(q.columns().get(1).visible()).isFalse();

        // SQL arrays and JSON arrays may be published as collections
        mvc.perform(post("/admin/queries/" + id).with(admin()).with(csrf()).param("name", name).param("datasourceId", demoId)
                        .param("sql", "SELECT customer, ARRAY[region, 'x'] AS tags, CAST('[{\"a\":1}]' AS VARCHAR) AS pos FROM sales")
                        .param("executionMode", "CALLER").param("keyMode", "ROWID").param("enabled", "true").param("cacheTtlSec", "3600"))
                .andExpect(status().is3xxRedirection());
        String collections = body(mvc.perform(post("/admin/queries/" + id + "/snapshot").with(admin()).with(csrf())
                .param("dbUser", "demo").param("dbPassword", "demo")).andExpect(status().isOk()).andReturn());
        assertThat(collections).contains("collection_tags").contains("Collection(Edm.String)").contains("collection_pos")
                .contains("Collection(" + name + "_pos)").contains("Struktur");
        mvc.perform(post("/admin/queries/" + id + "/columns").with(admin()).with(csrf()).param("visible_customer", "on")
                        .param("visible_tags", "on").param("collection_tags", "PRIMITIVE").param("visible_pos", "on").param("collection_pos", "COMPLEX"))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("message"));
        q = queryService.require(UUID.fromString(id));
        assertThat(q.columns()).extracting(c -> c.collection().name()).containsExactly("NONE", "PRIMITIVE", "COMPLEX");
        mvc.perform(post("/admin/queries/" + id + "/columns").with(admin()).with(csrf()).param("visible_customer", "on")
                        .param("visible_tags", "on").param("collection_tags", "PRIMITIVE").param("textcopy_tags", "on")
                        .param("visible_pos", "on").param("collection_pos", "COMPLEX").param("structure_pos", "Anzahl Int64 a\n"))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("message"));
        q = queryService.require(UUID.fromString(id));
        assertThat(q.columns().get(1).textCopy()).isTrue();
        assertThat(q.columns().get(2).structure()).extracting(at.jdbcodata.query.ComplexField::name).containsExactly("Anzahl");
        assertThat(q.columns().get(2).structure().get(0).edmType()).isEqualTo("Edm.Int64");
        mvc.perform(post("/admin/queries/" + id + "/columns").with(admin()).with(csrf()).param("structure_pos", "kaputt"))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/queries/" + id + "/columns").with(admin()).with(csrf()).param("collection_customer", "PRIMITIVE"))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("error"));

        mvc.perform(post("/admin/queries/" + id).with(admin()).with(csrf())
                        .param("name", name).param("datasourceId", demoId)
                        .param("sql", "SELECT customer FROM sales WHERE sale_year = :y")
                        .param("parameters[0].name", "y").param("parameters[0].type", "INT")
                        .param("executionMode", "CALLER").param("keyMode", "ROWID").param("cacheTtlSec", "10"))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("message"));
        assertThat(queryService.require(UUID.fromString(id)).version()).isEqualTo(3); // 2 = the collection SQL above

        mvc.perform(post("/admin/queries/" + id).with(admin()).with(csrf()).param("name", "Sales").param("datasourceId", demoId).param("sql", "SELECT 1"))
                .andExpect(status().isOk());
        mvc.perform(post("/admin/queries/" + id).with(admin()).with(csrf()).param("name", "").param("datasourceId", demoId).param("sql", "SELECT 1"))
                .andExpect(status().isOk());

        String withVersions = body(mvc.perform(MockMvcRequestBuilders.get("/admin/queries/" + id).with(admin())).andExpect(status().isOk()).andReturn());
        assertThat(withVersions).contains("Wiederherstellen");
        mvc.perform(post("/admin/queries/" + id + "/versions/1/restore").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/queries/" + id)).andExpect(flash().attributeExists("message"));
        assertThat(queryService.require(UUID.fromString(id)).version()).isEqualTo(4);
        mvc.perform(post("/admin/queries/" + id + "/versions/99/restore").with(admin()).with(csrf()))
                .andExpect(status().isNotFound());

        mvc.perform(post("/admin/queries/" + id + "/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/queries")).andExpect(flash().attributeExists("message"));
        assertThat(queryService.findById(UUID.fromString(id))).isEmpty();
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries/" + id).with(admin())).andExpect(status().isNotFound());
    }
}
