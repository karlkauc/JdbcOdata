package at.jdbcodata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import at.jdbcodata.testutil.DemoProfileTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Forms from an expired session (missing or stale CSRF token) lead back to a usable page instead of a bare 403. */
class ExpiredFormTest extends DemoProfileTest {

    @Autowired
    WebApplicationContext context;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }

    @Test
    void staleLoginFormRedirectsToLoginWithNotice() throws Exception {
        mvc.perform(post("/login").param("username", "admin").param("password", "x"))
                .andExpect(redirectedUrl("/login?expired"));
        mvc.perform(post("/login").with(csrf().useInvalidToken()).param("username", "admin").param("password", "x"))
                .andExpect(redirectedUrl("/login?expired"));
        String login = mvc.perform(MockMvcRequestBuilders.get("/login").param("expired", ""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(login).contains("Your session has expired");
    }

    @Test
    void signedInUserGoesBackToTheFormWithMessage() throws Exception {
        mvc.perform(post("/admin/cache/invalidate").with(user("admin").roles("ADMIN"))
                        .header("Referer", "http://localhost/admin/cache?x=1"))
                .andExpect(redirectedUrl("/admin/cache?x=1"))
                .andExpect(flash().attribute("error", ExpiredFormHandler.EXPIRED_MESSAGE));
        // foreign or missing referer: back to the overview
        mvc.perform(post("/admin/cache/invalidate").with(user("admin").roles("ADMIN")).header("Referer", "https://evil.example/admin"))
                .andExpect(redirectedUrl("/admin"));
        mvc.perform(post("/admin/cache/invalidate").with(user("admin").roles("ADMIN")))
                .andExpect(redirectedUrl("/admin"));
    }

    @Test
    void htmxRequestsGetARedirectHeader() throws Exception {
        mvc.perform(post("/admin/queries/test").header("HX-Request", "true"))
                .andExpect(status().isForbidden())
                .andExpect(header().string("HX-Redirect", "/login?expired"));
    }

    @Test
    void otherDenialsStayForbiddenAndFaviconIsPublic() throws Exception {
        mvc.perform(post("/admin/cache/invalidate").with(user("viewer").roles("VIEWER")).with(csrf()))
                .andExpect(status().isForbidden());
        int favicon = mvc.perform(MockMvcRequestBuilders.get("/favicon.ico")).andReturn().getResponse().getStatus();
        assertThat(favicon).isNotEqualTo(302).isNotEqualTo(401);
    }
}
