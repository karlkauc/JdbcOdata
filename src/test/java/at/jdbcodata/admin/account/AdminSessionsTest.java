package at.jdbcodata.admin.account;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.testutil.DemoProfileTest;
import jakarta.servlet.Filter;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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
    @Autowired
    @Qualifier("springSessionRepositoryFilter")
    Filter sessions;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(sessions).apply(springSecurity()).build();
    }

    private Cookie signIn(String user, String password) throws Exception {
        Cookie session = mvc.perform(formLogin().user(user).password(password))
                .andExpect(redirectedUrl("/admin")).andReturn().getResponse().getCookie("SESSION");
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").cookie(session)).andExpect(status().isOk());
        return session;
    }

    @Test
    void passwordChangeAndDisablingEndSessions() throws Exception {
        String name = "sess_" + UUID.randomUUID().toString().substring(0, 8);
        AdminUser account = accounts.create(new AdminUserService.AdminUserSpec(name, "first-pass-1", AdminRole.VIEWER, true), false, false);

        Cookie session = signIn(name, "first-pass-1");
        accounts.update(account.id(), new AdminUserService.AdminUserSpec(name, "second-pass-2", AdminRole.VIEWER, true));
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").cookie(session)).andExpect(redirectedUrl("/login?ended"));

        accounts.changeOwnPassword(name, "second-pass-2", "third-pass-3");
        Cookie again = signIn(name, "third-pass-3");
        accounts.update(account.id(), new AdminUserService.AdminUserSpec(name, null, AdminRole.VIEWER, true));
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").cookie(again)).andExpect(status().isOk()); // nothing relevant changed
        accounts.update(account.id(), new AdminUserService.AdminUserSpec(name, null, AdminRole.VIEWER, false));
        mvc.perform(MockMvcRequestBuilders.get("/admin/queries").cookie(again)).andExpect(redirectedUrl("/login?ended"));
        accounts.delete(account.id());
    }
}
