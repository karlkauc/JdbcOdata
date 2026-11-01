package at.jdbcodata.admin.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

class PasswordChangeTest extends DemoProfileTest {

    private static final String PREFIX = "acc_";
    private static final String TEMPORARY = "temporary-pw";
    private static final String CHOSEN = "my-own-password";

    @Autowired
    WebApplicationContext context;
    @Autowired
    AdminUserService accounts;
    @Autowired
    AuditService audit;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    /** The context is shared: a further ADMIN would let other tests take the role of the last one away. */
    @AfterEach
    void removeAccounts() {
        accounts.findAll().stream().filter(a -> a.username().startsWith(PREFIX)).forEach(a -> accounts.delete(a.id()));
    }

    @Test
    void newAccountIsLedToThePasswordPage() throws Exception {
        String name = account(AdminRole.ADMIN);

        mvc.perform(MockMvcRequestBuilders.get("/admin").with(user(name).roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", endsWith("/admin/password")));
        mvc.perform(MockMvcRequestBuilders.get("/admin/password").with(user(name).roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/admin/datasources").with(user(name).roles("ADMIN")).with(csrf()))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", endsWith("/admin/password")));
    }

    @Test
    void restApiRefusesUntilThePasswordIsChanged() throws Exception {
        String name = account(AdminRole.ADMIN);
        assertThat(get("/api/v1/datasources", name, TEMPORARY).statusCode()).isEqualTo(403);

        change(name, "ADMIN", TEMPORARY, CHOSEN, CHOSEN);

        assertThat(get("/api/v1/datasources", name, CHOSEN).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/datasources", name, TEMPORARY).statusCode()).isEqualTo(401);
    }

    @Test
    void viewerCanChangeTheirOwnPassword() throws Exception {
        String name = account(AdminRole.VIEWER);

        change(name, "VIEWER", TEMPORARY, CHOSEN, CHOSEN);

        assertThat(accounts.mustChangePassword(name)).isFalse();
        mvc.perform(MockMvcRequestBuilders.get("/admin").with(user(name).roles("VIEWER"))).andExpect(status().isOk());
        assertThat(audit.latest(20)).anyMatch(e -> "PASSWORD_CHANGE".equals(e.action()) && name.equals(e.entityName()));
    }

    @Test
    void wrongInputChangesNothing() throws Exception {
        String name = account(AdminRole.ADMIN);

        assertThat(error(change(name, "ADMIN", "wrong-password", CHOSEN, CHOSEN))).contains("current password is wrong");
        assertThat(error(change(name, "ADMIN", TEMPORARY, CHOSEN, "something-else"))).contains("do not match");
        assertThat(error(change(name, "ADMIN", TEMPORARY, "short", "short"))).contains("at least 8");
        assertThat(error(change(name, "ADMIN", TEMPORARY, TEMPORARY, TEMPORARY))).contains("must differ");

        assertThat(accounts.mustChangePassword(name)).isTrue();
    }

    @Test
    void resetByAnotherAdminIsTemporaryAgain() throws Exception {
        String name = account(AdminRole.ADMIN);
        change(name, "ADMIN", TEMPORARY, CHOSEN, CHOSEN);
        UUID id = accounts.findAll().stream().filter(a -> a.username().equals(name)).findFirst().orElseThrow().id();

        accounts.update(id, new AdminUserService.AdminUserSpec(null, "set-by-admin", null, null));

        assertThat(accounts.mustChangePassword(name)).isTrue();
    }

    @Test
    void settingTheOwnPasswordIsFinal() throws Exception {
        String name = account(AdminRole.ADMIN);
        UUID id = accounts.findAll().stream().filter(a -> a.username().equals(name)).findFirst().orElseThrow().id();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(name, null,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        try {
            accounts.update(id, new AdminUserService.AdminUserSpec(null, CHOSEN, null, null));
        } finally {
            SecurityContextHolder.clearContext();
        }

        assertThat(accounts.mustChangePassword(name)).isFalse();
    }

    @Test
    void changingRoleOnlyKeepsTheState() throws Exception {
        String name = account(AdminRole.ADMIN);
        UUID id = accounts.findAll().stream().filter(a -> a.username().equals(name)).findFirst().orElseThrow().id();

        accounts.update(id, new AdminUserService.AdminUserSpec(null, null, AdminRole.VIEWER, null));

        assertThat(accounts.mustChangePassword(name)).isTrue();
    }

    @Test
    void providerUsersHaveNoPasswordPage() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/admin/password").with(user("oidc:anna").roles("ADMIN")))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", endsWith("/admin")));
        mvc.perform(post("/admin/password").with(user("oidc:anna").roles("ADMIN")).with(csrf())
                .param("current", "x").param("password", CHOSEN).param("confirm", CHOSEN))
                .andExpect(status().is3xxRedirection()).andExpect(header().string("Location", endsWith("/admin")));
    }

    private String account(AdminRole role) {
        String name = PREFIX + UUID.randomUUID().toString().substring(0, 8);
        accounts.create(new AdminUserService.AdminUserSpec(name, TEMPORARY, role, true));
        return name;
    }

    private MvcResult change(String name, String role, String current, String password, String confirm) throws Exception {
        return mvc.perform(post("/admin/password").with(user(name).roles(role)).with(csrf())
                .param("current", current).param("password", password).param("confirm", confirm))
                .andExpect(status().is3xxRedirection()).andReturn();
    }

    private static String error(MvcResult result) {
        return String.valueOf(result.getFlashMap().get("error"));
    }
}
