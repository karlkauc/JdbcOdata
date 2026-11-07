package at.jdbcodata.transfer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.identity.ServiceUserService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class TransferUiTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    ServiceUserService serviceUsers;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void pageAndDownloadForViewers() throws Exception {
        String page = mvc.perform(MockMvcRequestBuilders.get("/admin/transfer").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(page).contains("Download configuration", "Check file", "Export / import");

        var download = mvc.perform(MockMvcRequestBuilders.get("/admin/transfer/export").with(user("viewer").roles("VIEWER")))
                .andExpect(status().isOk()).andReturn().getResponse();
        assertThat(download.getContentType()).startsWith("application/yaml");
        assertThat(download.getContentAsString()).startsWith("format: 1\n");
    }

    @Test
    void checkThenApply() throws Exception {
        String group = "uigrp_" + UUID.randomUUID().toString().substring(0, 8);
        String yaml = "format: 1\ngroups:\n- name: " + group + "\n  description: <b>from test</b>\n";
        MockMultipartFile file = new MockMultipartFile("file", "config.yaml", "application/yaml", yaml.getBytes(StandardCharsets.UTF_8));

        String preview = mvc.perform(multipart("/admin/transfer/preview").file(file).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("What the import would do", group, "badge ok", "Apply import")
                .contains("&lt;b&gt;from test&lt;/b&gt;").doesNotContain("<b>from test</b>");
        assertThat(serviceUsers.findAllGroups()).noneMatch(g -> g.name().equals(group));

        String applied = mvc.perform(multipart("/admin/transfer/apply").param("content", yaml)
                        .param("overwriteConnections", "false").param("prune", "false")
                        .with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(applied).contains("Import applied: 1 created").doesNotContain("Apply import");
        assertThat(serviceUsers.findAllGroups()).anyMatch(g -> g.name().equals(group));
        serviceUsers.findAllGroups().stream().filter(g -> g.name().equals(group)).forEach(g -> serviceUsers.deleteGroup(g.id()));
    }

    @Test
    void errorsAreShownInPlace() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.yaml", "application/yaml", "datasources: []\n".getBytes(StandardCharsets.UTF_8));
        String preview = mvc.perform(multipart("/admin/transfer/preview").file(file).with(user("admin").roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(preview).contains("flash err", "&#39;format: 1&#39; is missing");
    }

    @Test
    void viewersCannotImport() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "x.yaml", "application/yaml", "format: 1\n".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/admin/transfer/preview").file(file).with(user("viewer").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
        mvc.perform(multipart("/admin/transfer/apply").param("content", "format: 1\n").with(user("viewer").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
    }
}
