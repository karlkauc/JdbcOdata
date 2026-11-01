package at.jdbcodata.security;

import static org.assertj.core.api.Assertions.assertThat;

import at.jdbcodata.audit.AuditService;
import at.jdbcodata.testutil.DemoProfileTest;
import java.io.IOException;
import java.net.CookieHandler;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

/** The browser flow against a real Keycloak: sign-in page, callback, roles from the groups claim and sign-out. */
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
        "spring.security.oauth2.client.registration.keycloak.client-id=jdbcodata",
        "spring.security.oauth2.client.registration.keycloak.client-secret=test-client-secret",
        "spring.security.oauth2.client.registration.keycloak.client-name=Keycloak",
        "spring.security.oauth2.client.registration.keycloak.scope=openid,profile",
        "jdbcodata.security.oidc.admin-values=jdbcodata-admins",
        "jdbcodata.security.oidc.viewer-values=jdbcodata-viewers"
})
class KeycloakLoginIT extends DemoProfileTest {

    private static final Pattern FORM_ACTION = Pattern.compile("action=\"([^\"]+)\"");
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Container
    static GenericContainer<?> keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.2").withExposedPorts(8080)
            .withCopyFileToContainer(MountableFile.forClasspathResource("keycloak/jdbcodata-realm.json"),
                    "/opt/keycloak/data/import/jdbcodata-realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/jdbcodata/.well-known/openid-configuration").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)));

    @Autowired
    private AuditService audit;

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.provider.keycloak.issuer-uri", KeycloakLoginIT::issuer);
    }

    private static String issuer() {
        return "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080) + "/realms/jdbcodata";
    }

    @Test
    void groupGrantsAdminRole() throws Exception {
        Browser browser = new Browser();
        HttpResponse<String> admin = browser.signIn("anna", "anna-pw");

        assertThat(admin.uri().getPath()).isEqualTo("/admin");
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(admin.body()).contains("oidc:anna");
        assertThat(browser.post("/admin/no-such-page", admin).statusCode()).isEqualTo(404);
        assertThat(audit.latest(50)).anyMatch(e -> "oidc:anna".equals(e.adminUser()) && "LOGIN".equals(e.action()));
    }

    @Test
    void groupGrantsViewerRole() throws Exception {
        Browser browser = new Browser();
        HttpResponse<String> admin = browser.signIn("vera", "vera-pw");

        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(browser.post("/admin/no-such-page", admin).statusCode()).isEqualTo(403);
    }

    @Test
    void userWithoutGroupIsRejected() throws Exception {
        Browser browser = new Browser();
        HttpResponse<String> result = browser.signIn("nora", "nora-pw");

        assertThat(result.uri().toString()).endsWith("/login?denied");
        assertThat(result.body()).contains("has no access");
        assertThat(browser.get(url("/admin")).uri().getPath()).isEqualTo("/login");
        assertThat(audit.latest(50)).filteredOn(e -> "oidc:nora".equals(e.adminUser()))
                .extracting(e -> e.action()).containsExactly("LOGIN_FAILED");
    }

    @Test
    void madeUpCallbackLeavesNoAuditRecord() throws Exception {
        Browser browser = new Browser();
        long before = audit.latest(500).size();
        HttpResponse<String> result = browser.get(url("/login/oauth2/code/keycloak?code=made-up&state=made-up"));

        assertThat(result.uri().toString()).endsWith("/login?ssofailed");
        assertThat(audit.latest(500)).hasSize((int) before);
    }

    @Test
    void signOutEndsTheProviderSession() throws Exception {
        Browser browser = new Browser();
        HttpResponse<String> admin = browser.signIn("anna", "anna-pw");
        assertThat(admin.headers().firstValue("Content-Security-Policy").orElseThrow())
                .contains("form-action 'self' " + URI.create(issuer()).resolve("/").toString().replaceAll("/$", ""));

        HttpResponse<String> toProvider = browser.post("/logout", admin);
        assertThat(toProvider.headers().firstValue("Location").orElseThrow()).startsWith(issuer());
        HttpResponse<String> signedOut = browser.get(toProvider.headers().firstValue("Location").orElseThrow());
        assertThat(signedOut.uri().toString()).endsWith("/login?logout");

        // the provider asks for the password again
        HttpResponse<String> page = browser.get(url("/oauth2/authorization/keycloak"));
        assertThat(page.uri().toString()).startsWith(issuer());
    }

    @Test
    void localAccountsKeepWorkingNextToOidc() throws Exception {
        String page = new Browser().get(url("/login")).body();
        assertThat(page).contains("name=\"password\"").contains("Sign in with Keycloak");
        assertThat(get("/api/v1/datasources", ADMIN, ADMIN_PASSWORD).statusCode()).isEqualTo(200);
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    /**
     * Keeps every cookie for every URL. Keycloak marks its cookies Secure on http://localhost, which browsers accept
     * as a secure context but the JDK cookie store does not.
     */
    private static final class AllCookies extends CookieHandler {

        private final Map<String, String> cookies = new ConcurrentHashMap<>();

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> headers) {
            return Map.of("Cookie", List.of(cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining("; "))));
        }

        @Override
        public void put(URI uri, Map<String, List<String>> headers) {
            for (String header : headers.getOrDefault("Set-Cookie", List.of())) {
                String[] cookie = header.split(";", 2)[0].split("=", 2);
                if (cookie[1].isEmpty() || header.contains("Max-Age=0")) {
                    cookies.remove(cookie[0]);
                } else {
                    cookies.put(cookie[0], cookie[1]);
                }
            }
        }
    }

    /** A browser reduced to cookies, redirects and forms. */
    private final class Browser {

        private final HttpClient client = HttpClient.newBuilder().cookieHandler(new AllCookies())
                .followRedirects(HttpClient.Redirect.NEVER).build();

        HttpResponse<String> signIn(String user, String password) throws IOException, InterruptedException {
            HttpResponse<String> form = get(url("/oauth2/authorization/keycloak"));
            assertThat(form.statusCode()).isEqualTo(200);
            Matcher action = FORM_ACTION.matcher(form.body());
            assertThat(action.find()).isTrue();
            String body = "username=" + user + "&password=" + password + "&credentialId=";
            return follow(send(action.group(1).replace("&amp;", "&"), body));
        }

        HttpResponse<String> get(String url) throws IOException, InterruptedException {
            return follow(client.send(page(URI.create(url)), HttpResponse.BodyHandlers.ofString()));
        }

        HttpResponse<String> post(String path, HttpResponse<String> page) throws IOException, InterruptedException {
            Matcher csrf = CSRF.matcher(page.body());
            assertThat(csrf.find()).isTrue();
            return send(url(path), "_csrf=" + URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8));
        }

        private HttpResponse<String> send(String url, String form) throws IOException, InterruptedException {
            return client.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
        }

        private HttpResponse<String> follow(HttpResponse<String> response) throws IOException, InterruptedException {
            for (int i = 0; i < 10 && response.statusCode() / 100 == 3; i++) {
                URI next = response.uri().resolve(response.headers().firstValue("Location").orElseThrow());
                response = client.send(page(next), HttpResponse.BodyHandlers.ofString());
            }
            return response;
        }

        private HttpRequest page(URI uri) {
            return HttpRequest.newBuilder(uri).header("Accept", "text/html").build();
        }
    }
}
