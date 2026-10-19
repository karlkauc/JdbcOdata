package at.jdbcodata.admin.ui;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.datasource.DatasourceService;
import at.jdbcodata.identity.FixedCredentialService;
import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.query.QueryService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class AdminUiM3Test extends DemoProfileTest {

    @Autowired WebApplicationContext context;
    @Autowired DatasourceService datasourceService;
    @Autowired ServiceUserService userService;
    @Autowired FixedCredentialService fixedService;
    @Autowired QueryService queryService;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor admin() {
        return user("admin").roles("ADMIN");
    }

    private static SecurityMockMvcRequestPostProcessors.UserRequestPostProcessor viewer() {
        return user("viewer").roles("VIEWER");
    }

    private String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString();
    }

    private String page(String path) throws Exception {
        return body(mvc.perform(MockMvcRequestBuilders.get(path).with(admin())).andExpect(status().isOk()).andReturn());
    }

    private static String idFrom(MvcResult r) {
        String location = r.getResponse().getRedirectedUrl();
        return location.substring(location.lastIndexOf('/') + 1);
    }

    @Test
    void serviceUserAndGroupPages() throws Exception {
        String demoId = datasourceService.findByName("demo").orElseThrow().id().toString();
        assertThat(page("/admin/users")).contains("Service users");
        assertThat(page("/admin/users/new")).contains("svc_");

        assertThat(body(mvc.perform(post("/admin/users").with(admin()).with(csrf()).param("username", "").param("password", "x"))
                .andExpect(status().isOk()).andReturn())).contains("field-error");
        assertThat(body(mvc.perform(post("/admin/users").with(admin()).with(csrf()).param("username", "svc_ui").param("password", "short"))
                .andExpect(status().isOk()).andReturn())).contains("8 characters");

        String name = "svc_ui_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mvc.perform(post("/admin/users").with(admin()).with(csrf())
                        .param("username", name).param("displayName", "UI").param("password", "password-1").param("enabled", "true"))
                .andExpect(status().is3xxRedirection()).andExpect(flash().attributeExists("message")).andReturn();
        String id = idFrom(created);
        assertThat(page("/admin/users/" + id)).contains(name).contains("Stored database credentials");

        mvc.perform(post("/admin/users/" + id).with(admin()).with(csrf()).param("username", name).param("forcePassthrough", "true").param("enabled", "true"))
                .andExpect(redirectedUrl("/admin/users/" + id));
        assertThat(userService.require(UUID.fromString(id)).forcePassthrough()).isTrue();
        assertThat(body(mvc.perform(post("/admin/users/" + id).with(admin()).with(csrf()).param("username", "demo_dup").param("password", "short"))
                .andExpect(status().isOk()).andReturn())).contains("8 characters");

        mvc.perform(post("/admin/users/" + id + "/credentials").with(admin()).with(csrf())
                        .param("datasourceId", demoId).param("dbUsername", "demo").param("dbPassword", "demo"))
                .andExpect(redirectedUrl("/admin/users/" + id)).andExpect(flash().attributeExists("message"));
        assertThat(page("/admin/users/" + id)).contains("Remove");
        mvc.perform(post("/admin/users/" + id + "/credentials").with(admin()).with(csrf())
                        .param("datasourceId", demoId).param("dbUsername", "").param("dbPassword", ""))
                .andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/users/" + id + "/credentials/" + demoId + "/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/users/" + id)).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/users/" + id + "/credentials/" + demoId + "/delete").with(admin()).with(csrf()))
                .andExpect(flash().attributeExists("error"));

        assertThat(page("/admin/groups")).contains("Create group");
        String groupName = "grp_ui_" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/admin/groups").with(admin()).with(csrf()).param("name", groupName).param("description", "d"))
                .andExpect(redirectedUrl("/admin/groups")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/groups").with(admin()).with(csrf()).param("name", groupName))
                .andExpect(flash().attributeExists("error"));
        String groupId = userService.findAllGroups().stream().filter(g -> g.name().equals(groupName)).findFirst().orElseThrow().id().toString();
        mvc.perform(post("/admin/users/" + id).with(admin()).with(csrf()).param("username", name).param("groupIds", groupId).param("enabled", "true"))
                .andExpect(redirectedUrl("/admin/users/" + id));
        assertThat(page("/admin/groups")).contains(name);
        assertThat(page("/admin/users")).contains(groupName);
        mvc.perform(post("/admin/groups/" + groupId + "/delete").with(admin()).with(csrf())).andExpect(redirectedUrl("/admin/groups"));
        mvc.perform(post("/admin/users/" + id + "/delete").with(admin()).with(csrf())).andExpect(redirectedUrl("/admin/users"));
        mvc.perform(MockMvcRequestBuilders.get("/admin/users/" + id).with(admin())).andExpect(status().isNotFound());
    }

    @Test
    void fixedAccountsCacheStatsAuditAdminsSettingsPages() throws Exception {
        String demoId = datasourceService.findByName("demo").orElseThrow().id().toString();
        assertThat(page("/admin/accounts")).contains("Technical accounts");
        assertThat(page("/admin/accounts/new")).contains("DB password");
        assertThat(body(mvc.perform(post("/admin/accounts").with(admin()).with(csrf()).param("name", "").param("datasourceId", demoId).param("dbUsername", "x"))
                .andExpect(status().isOk()).andReturn())).contains("field-error");
        String accName = "tech_ui_" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mvc.perform(post("/admin/accounts").with(admin()).with(csrf())
                        .param("name", accName).param("datasourceId", demoId).param("dbUsername", "demo").param("dbPassword", "demo"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String accId = idFrom(created);
        assertThat(body(mvc.perform(post("/admin/accounts").with(admin()).with(csrf())
                .param("name", accName).param("datasourceId", demoId).param("dbUsername", "demo").param("dbPassword", "demo"))
                .andExpect(status().isOk()).andReturn())).contains("already exists");
        assertThat(page("/admin/accounts/" + accId)).contains(accName).contains("Test connection");
        assertThat(body(mvc.perform(post("/admin/accounts/" + accId + "/test").with(admin()).with(csrf())).andExpect(status().isOk()).andReturn()))
                .contains("Connection successful");
        mvc.perform(post("/admin/accounts/" + accId).with(admin()).with(csrf())
                        .param("name", accName).param("description", "edited").param("datasourceId", demoId).param("dbUsername", "demo").param("dbPassword", ""))
                .andExpect(redirectedUrl("/admin/accounts/" + accId));
        assertThat(fixedService.require(UUID.fromString(accId)).description()).isEqualTo("edited");
        mvc.perform(post("/admin/accounts/" + accId).with(admin()).with(csrf()).param("name", "").param("datasourceId", demoId).param("dbUsername", "x"))
                .andExpect(status().isOk());

        // query editor with FIXED mode and grants
        String qName = "FixedUi_" + UUID.randomUUID().toString().substring(0, 8).replace('-', '_');
        MvcResult q = mvc.perform(post("/admin/queries").with(admin()).with(csrf())
                        .param("name", qName).param("datasourceId", demoId).param("sql", "SELECT id FROM sales")
                        .param("executionMode", "FIXED").param("fixedCredentialId", accId).param("keyMode", "ROWID").param("enabled", "true").param("cacheTtlSec", "60"))
                .andExpect(status().is3xxRedirection()).andReturn();
        String qId = idFrom(q);
        String editor = page("/admin/queries/" + qId);
        assertThat(editor).contains("Grants").contains(accName);
        mvc.perform(post("/admin/queries/" + qId + "/grants").with(admin()).with(csrf()).param("all", "on"))
                .andExpect(redirectedUrl("/admin/queries/" + qId)).andExpect(flash().attributeExists("message"));
        assertThat(queryService.grants(UUID.fromString(qId))).hasSize(1);
        assertThat(page("/admin/queries/" + qId)).contains("1 grant(s) active");
        mvc.perform(post("/admin/accounts/" + accId + "/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/accounts/" + accId)).andExpect(flash().attributeExists("error"));
        mvc.perform(post("/admin/queries/" + qId + "/delete").with(admin()).with(csrf())).andExpect(redirectedUrl("/admin/queries"));
        mvc.perform(post("/admin/accounts/" + accId + "/delete").with(admin()).with(csrf())).andExpect(redirectedUrl("/admin/accounts"));

        // cache, stats, audit, admins, settings
        assertThat(page("/admin/cache")).contains("Result cache");
        mvc.perform(post("/admin/cache/invalidate").with(admin()).with(csrf())).andExpect(redirectedUrl("/admin/cache")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/cache/invalidate").with(admin()).with(csrf()).param("queryId", UUID.randomUUID().toString()).param("dbUser", "x"))
                .andExpect(redirectedUrl("/admin/cache"));
        assertThat(page("/admin/stats")).contains("Statistics").contains("Top queries");
        assertThat(page("/admin/stats?period=24h&queryName=Sales&caller=demo&status=OK")).contains("Recent executions");
        assertThat(page("/admin/stats?period=30d")).contains("30 days");
        assertThat(page("/admin/stats?period=90d")).contains("Statistics");
        assertThat(page("/admin/audit")).contains("Audit log");
        assertThat(page("/admin/settings")).contains("Master key").contains("configuration");

        assertThat(page("/admin/admins")).contains("Admin accounts").contains("admin");
        String adminName = "ui_admin_" + UUID.randomUUID().toString().substring(0, 8);
        mvc.perform(post("/admin/admins").with(admin()).with(csrf()).param("username", adminName).param("password", "password-1").param("role", "VIEWER"))
                .andExpect(redirectedUrl("/admin/admins")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/admins").with(admin()).with(csrf()).param("username", adminName).param("password", "password-1"))
                .andExpect(flash().attributeExists("error"));
        String adminId = body(mvc.perform(MockMvcRequestBuilders.get("/api/v1/admins").with(admin())).andReturn());
        String newId = adminId.substring(adminId.indexOf(adminName) - 60);
        newId = newId.substring(newId.indexOf("\"id\":\"") + 6, newId.indexOf("\"id\":\"") + 42);
        mvc.perform(post("/admin/admins/" + newId).with(admin()).with(csrf()).param("role", "ADMIN").param("enabled", "true").param("password", ""))
                .andExpect(redirectedUrl("/admin/admins")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/admins/" + newId + "/delete").with(admin()).with(csrf()))
                .andExpect(redirectedUrl("/admin/admins")).andExpect(flash().attributeExists("message"));
        mvc.perform(post("/admin/admins/" + newId + "/delete").with(admin()).with(csrf())).andExpect(flash().attributeExists("error"));
    }

    @Test
    void viewerMayReadButNotWrite() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/admin/stats").with(viewer())).andExpect(status().isOk());
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").with(viewer())).andExpect(status().isOk());
        mvc.perform(post("/admin/cache/invalidate").with(viewer()).with(csrf())).andExpect(status().isForbidden());
        mvc.perform(post("/admin/groups").with(viewer()).with(csrf()).param("name", "x_y")).andExpect(status().isForbidden());
    }
}
