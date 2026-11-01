package at.jdbcodata.apikey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.testutil.DemoProfileTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class ApiKeyUiTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    ApiKeyService keys;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void secretIsShownOnceAfterCreation() throws Exception {
        String name = "ui-" + UUID.randomUUID().toString().substring(0, 8);
        MvcResult created = mvc.perform(post("/admin/api-keys").with(user("admin").roles("ADMIN")).with(csrf())
                .param("name", name).param("target", "role:VIEWER").param("expiresInDays", "90"))
                .andExpect(status().is3xxRedirection()).andReturn();
        ApiKeyService.Created flash = (ApiKeyService.Created) created.getFlashMap().get("created");
        assertThat(flash.secret()).startsWith("jdo_");

        String withSecret = mvc.perform(MockMvcRequestBuilders.get("/admin/api-keys").with(user("admin").roles("ADMIN")).flashAttrs(created.getFlashMap()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(withSecret).contains(flash.secret()).contains("REST API, VIEWER").contains("active");

        String later = mvc.perform(MockMvcRequestBuilders.get("/admin/api-keys").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(later).contains(name).doesNotContain(flash.secret());
    }

    @Test
    void viewerCannotCreateOrRevoke() throws Exception {
        ApiKey key = keys.create("ui-" + UUID.randomUUID().toString().substring(0, 8), null, at.jdbcodata.admin.account.AdminRole.VIEWER, 30).key();

        mvc.perform(post("/admin/api-keys").with(user("viewer").roles("VIEWER")).with(csrf())
                .param("name", "nope").param("target", "role:ADMIN").param("expiresInDays", "90")).andExpect(status().isForbidden());
        mvc.perform(post("/admin/api-keys/" + key.id() + "/revoke").with(user("viewer").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminRevokes() throws Exception {
        ApiKeyService.Created key = keys.create("ui-" + UUID.randomUUID().toString().substring(0, 8), null, at.jdbcodata.admin.account.AdminRole.VIEWER, 30);

        mvc.perform(post("/admin/api-keys/" + key.key().id() + "/revoke").with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection());

        assertThat(keys.authenticate(key.secret())).isEmpty();
    }

    @Test
    void invalidInputComesBackAsMessage() throws Exception {
        MvcResult result = mvc.perform(post("/admin/api-keys").with(user("admin").roles("ADMIN")).with(csrf())
                .param("name", "!").param("target", "role:VIEWER").param("expiresInDays", "90"))
                .andExpect(status().is3xxRedirection()).andReturn();

        assertThat(result.getFlashMap().get("error").toString()).contains("Name must be");
    }
}
