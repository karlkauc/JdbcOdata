package at.jdbcodata.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import at.jdbcodata.testutil.DemoProfileTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class LoginAuditTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    AuditService audit;

    private List<String> actionsOf(String user, String type) {
        return audit.latest(1000).stream().filter(e -> e.adminUser().equals(user) && e.entityType().equals(type))
                .map(AuditEntry::action).toList();
    }

    @Test
    void formLoginAndLogoutAreRecorded() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
        int before = actionsOf(ADMIN, "ADMIN_LOGIN").size();
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin("/login").user(ADMIN).password(ADMIN_PASSWORD))
                .andReturn().getRequest().getSession();
        mvc.perform(post("/logout").with(csrf()).session(session));
        assertThat(actionsOf(ADMIN, "ADMIN_LOGIN").subList(0, 2)).containsExactly("LOGOUT", "LOGIN");
        assertThat(actionsOf(ADMIN, "ADMIN_LOGIN")).hasSize(before + 2);
    }

    @Test
    void wrongAdminPasswordIsRecordedButApiRequestsAreNoLogins() throws Exception {
        String name = "nobody-" + UUID.randomUUID();
        assertThat(get("/api/v1/audit", name, "wrong").statusCode()).isEqualTo(401);
        assertThat(actionsOf(name, "ADMIN_LOGIN")).containsExactly("LOGIN_FAILED");

        int before = actionsOf(ADMIN, "ADMIN_LOGIN").size();
        assertThat(get("/api/v1/audit", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
        assertThat(actionsOf(ADMIN, "ADMIN_LOGIN")).hasSize(before);
    }

    @Test
    void rejectedFeedLoginIsRecordedWithoutThePassword() throws Exception {
        String name = "nobody-" + UUID.randomUUID();
        assertThat(get("/odata/", name, "wrong-password").statusCode()).isEqualTo(401);
        assertThat(actionsOf(name, "FEED_LOGIN")).containsExactly("LOGIN_FAILED");
        assertThat(audit.latest(1000).stream().filter(e -> e.adminUser().equals(name)).findFirst().orElseThrow().details())
                .contains("\"ip\"").doesNotContain("wrong-password");
    }

    @Test
    void longNamesFitTheColumns() throws Exception {
        String name = "x".repeat(150) + UUID.randomUUID();
        assertThat(get("/api/v1/audit", name, "wrong").statusCode()).isEqualTo(401);
        assertThat(actionsOf(name.substring(0, 100), "ADMIN_LOGIN")).contains("LOGIN_FAILED");
    }
}
