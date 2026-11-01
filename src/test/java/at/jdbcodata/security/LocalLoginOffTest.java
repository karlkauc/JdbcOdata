package at.jdbcodata.security;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.testutil.DemoProfileTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/** With {@code local-login=false} no password gets anybody in. The provider URLs are never called here. */
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.idp.client-id=jdbcodata",
        "spring.security.oauth2.client.registration.idp.client-name=Corporate login",
        "spring.security.oauth2.client.registration.idp.authorization-grant-type=authorization_code",
        "spring.security.oauth2.client.registration.idp.redirect-uri={baseUrl}/login/oauth2/code/{registrationId}",
        "spring.security.oauth2.client.registration.idp.scope=openid",
        "spring.security.oauth2.client.provider.idp.authorization-uri=https://idp.invalid/authorize",
        "spring.security.oauth2.client.provider.idp.token-uri=https://idp.invalid/token",
        "spring.security.oauth2.client.provider.idp.jwk-set-uri=https://idp.invalid/jwks",
        "spring.security.oauth2.client.provider.idp.user-name-attribute=sub",
        "jdbcodata.security.oidc.local-login=false",
        "jdbcodata.security.oidc.admin-values=admins"
})
class LocalLoginOffTest extends DemoProfileTest {

    @Test
    void loginPageOffersOnlySingleSignOn() throws Exception {
        String page = get("/login", null, null).body();

        assertThat(page).contains("Sign in with Corporate login").contains("/oauth2/authorization/idp");
        assertThat(page).doesNotContain("name=\"password\"");
    }

    @Test
    void passwordsAreRefusedByUiAndApi() throws Exception {
        assertThat(get("/admin", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(302);
        assertThat(get("/api/v1/datasources", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(401);
    }

    @Test
    void uiRedirectsToTheLoginPage() throws Exception {
        assertThat(get("/admin", null, null).headers().firstValue("Location").orElseThrow()).endsWith("/login");
    }
}
