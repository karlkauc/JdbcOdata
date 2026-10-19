package at.jdbcodata.admin.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.testutil.DemoProfileTest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Disabling an account or changing its password or role ends its open admin UI sessions. */
class AdminSessionsTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;
    @Autowired
    AdminUserService accounts;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    private MockHttpSession signIn(String user, String password) throws Exception {
        MockHttpSession session = (MockHttpSession) mvc.perform(formLogin().user(user).password(password))
                .andExpect(redirectedUrl("/admin")).andReturn().getRequest().getSession(false);
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").session(session)).andExpect(status().isOk());
        return session;
    }

    @Test
    void passwordChangeAndDisablingEndSessions() throws Exception {
        String name = "sess_" + UUID.randomUUID().toString().substring(0, 8);
        AdminUser account = accounts.create(new AdminUserService.AdminUserSpec(name, "first-pass-1", AdminRole.VIEWER, true), false);

        MockHttpSession session = signIn(name, "first-pass-1");
        accounts.update(account.id(), new AdminUserService.AdminUserSpec(name, "second-pass-2", AdminRole.VIEWER, true));
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").session(session)).andExpect(redirectedUrl("/login?ended"));

        MockHttpSession again = signIn(name, "second-pass-2");
        accounts.update(account.id(), new AdminUserService.AdminUserSpec(name, null, AdminRole.VIEWER, true));
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").session(again)).andExpect(status().isOk()); // nothing relevant changed
        accounts.update(account.id(), new AdminUserService.AdminUserSpec(name, null, AdminRole.VIEWER, false));
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").session(again)).andExpect(redirectedUrl("/login?ended"));
        accounts.delete(account.id());
    }
}
