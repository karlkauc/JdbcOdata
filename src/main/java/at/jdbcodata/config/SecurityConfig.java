package at.jdbcodata.config;

import at.jdbcodata.admin.account.AdminUserService;
import at.jdbcodata.admin.account.PasswordChangeFilter;
import at.jdbcodata.apikey.ApiKeyAuthenticationFilter;
import at.jdbcodata.apikey.ApiKeyService;
import at.jdbcodata.audit.LoginAudit;
import at.jdbcodata.security.AdminOidcUserService;
import at.jdbcodata.security.LoginRateLimiter;
import at.jdbcodata.security.MetricsTokenMatcher;
import at.jdbcodata.security.OidcClients;
import at.jdbcodata.security.OidcFailureHandler;
import at.jdbcodata.security.OidcProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.oidc.web.logout.OidcClientInitiatedLogoutSuccessHandler;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

/**
 * Three security chains:
 * <ul>
 *   <li>{@code /odata/**}: open for Spring Security. The OData servlet reads the Basic Auth header itself and
 *       resolves the database identity (pass-through, stored credentials or technical account).</li>
 *   <li>{@code /api/**}: stateless Basic Auth against the admin accounts, no CSRF. GET for ADMIN and VIEWER,
 *       everything else ADMIN only.</li>
 *   <li>everything else (admin UI, actuator): session login form (Basic Auth also accepted), CSRF enabled,
 *       same role split as the API.</li>
 * </ul>
 * Admin accounts live in the {@code admin_user} table (see {@code AdminUserService}). With a client registration
 * the UI also signs in over OIDC, see {@link OidcProperties}; {@code local-login=false} then turns off password
 * logins in UI and API.
 */
@Configuration
public class SecurityConfig {

    /**
     * Admin UI: scripts and styles only from this origin (htmx, CodeMirror and admin.js are served locally); inline
     * styles stay allowed for CodeMirror and htmx indicators. No framing, forms only to this origin.
     */
    static final String CONTENT_SECURITY_POLICY = "default-src 'self'; script-src 'self'; "
            + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; connect-src 'self'; "
            + "object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'";

    /** The matcher has checked the token already. Requests with a wrong token go on to the UI chain and its login. */
    @Bean
    @Order(0)
    SecurityFilterChain metricsTokenChain(HttpSecurity http,
                                          @Value("${jdbcodata.security.metrics-token:}") String metricsToken) throws Exception {
        http.securityMatcher(new MetricsTokenMatcher(metricsToken))
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(1)
    SecurityFilterChain odataChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/odata/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                // Responses are per caller and validated by ETag; the servlet sets "private, no-cache".
                .headers(h -> h.cacheControl(c -> c.disable()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http, OidcProperties oidc, ApiKeyService keys, LoginRateLimiter limiter,
                                 LoginAudit audit, AdminUserService admins) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "VIEWER")
                        // a leaked key must not be able to mint keys that outlive its own expiry or revocation
                        .requestMatchers("/api/v1/api-keys/**").access((auth, context) -> new AuthorizationDecision(
                                !auth.get().getName().startsWith(ApiKeyService.CALLER_PREFIX)
                                        && auth.get().getAuthorities().stream().anyMatch(g -> "ROLE_ADMIN".equals(g.getAuthority()))))
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(new ApiKeyAuthenticationFilter(keys, limiter, audit), BasicAuthenticationFilter.class)
                .addFilterBefore(new PasswordChangeFilter(admins), AuthorizationFilter.class)
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        if (oidc.localLogin()) {
            http.httpBasic(Customizer.withDefaults());
        } else {
            http.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)));
        }
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain uiChain(HttpSecurity http, SessionRegistry sessionRegistry, OidcProperties oidc,
                                OidcClients clients, AdminOidcUserService oidcUsers,
                                OidcFailureHandler oidcFailure, AdminUserService admins) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login", "/error",
                                "/webjars/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers(PasswordChangeFilter.PAGE).authenticated()
                        .requestMatchers(HttpMethod.GET, "/admin/**", "/", "/actuator/**").hasAnyRole("ADMIN", "VIEWER")
                        .requestMatchers("/admin/stats/**").hasAnyRole("ADMIN", "VIEWER")
                        .requestMatchers("/logout").authenticated()
                        .anyRequest().hasRole("ADMIN"))
                .addFilterBefore(new PasswordChangeFilter(admins), AuthorizationFilter.class)
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
                // a form from an expired session (restart, timeout) leads back to the login instead of a bare 403
                .exceptionHandling(e -> e.accessDeniedHandler(new ExpiredFormHandler()))
                // registry, so AdminSessions can end sessions of disabled, deleted or changed accounts
                .sessionManagement(s -> s.maximumSessions(-1).sessionRegistry(sessionRegistry).expiredUrl("/login?ended"))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(contentSecurityPolicy(clients)))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        if (oidc.localLogin()) {
            http.formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/admin", false).permitAll())
                    .httpBasic(Customizer.withDefaults());
        }
        if (clients.configured()) {
            http.oauth2Login(o -> o.loginPage("/login").defaultSuccessUrl("/admin", false)
                    .userInfoEndpoint(u -> u.oidcUserService(oidcUsers)).failureHandler(oidcFailure));
            // ends the IdP session too: otherwise the next click on the sign-in link logs straight back in
            OidcClientInitiatedLogoutSuccessHandler logout = new OidcClientInitiatedLogoutSuccessHandler(clients.repository());
            logout.setPostLogoutRedirectUri("{baseUrl}/login?logout");
            logout.setDefaultTargetUrl("/login?logout");
            http.logout(l -> l.logoutSuccessHandler(logout));
        }
        return http.build();
    }

    /** Browsers apply form-action to redirects after a form too, and sign-out is a form that ends at the IdP. */
    private static String contentSecurityPolicy(OidcClients clients) {
        String origins = String.join(" ", clients.logoutOrigins());
        return origins.isEmpty() ? CONTENT_SECURITY_POLICY
                : CONTENT_SECURITY_POLICY.replace("form-action 'self'", "form-action 'self' " + origins);
    }

    @Bean
    SessionRegistry sessionRegistry() {
        return new SessionRegistryImpl();
    }

    /** Lets the session registry forget sessions that time out or log out. */
    @Bean
    static HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // NFR-04; existing cost-10 hashes keep working
    }
}
