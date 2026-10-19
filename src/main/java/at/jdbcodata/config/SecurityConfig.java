package at.jdbcodata.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

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
 * Admin accounts live in the {@code admin_user} table (see {@code AdminUserService}).
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
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/api/**").hasAnyRole("ADMIN", "VIEWER")
                        .anyRequest().hasRole("ADMIN"))
                .httpBasic(Customizer.withDefaults())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(3)
    SecurityFilterChain uiChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/login", "/error",
                                "/webjars/**", "/css/**", "/js/**", "/favicon.ico").permitAll()
                        .requestMatchers(HttpMethod.GET, "/admin/**", "/", "/actuator/**").hasAnyRole("ADMIN", "VIEWER")
                        .requestMatchers("/admin/stats/**").hasAnyRole("ADMIN", "VIEWER")
                        .requestMatchers("/logout").authenticated()
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/admin", false).permitAll())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
                // a form from an expired session (restart, timeout) leads back to the login instead of a bare 403
                .exceptionHandling(e -> e.accessDeniedHandler(new ExpiredFormHandler()))
                .headers(h -> h.contentSecurityPolicy(c -> c.policyDirectives(CONTENT_SECURITY_POLICY))
                        .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12); // NFR-04; existing cost-10 hashes keep working
    }
}
