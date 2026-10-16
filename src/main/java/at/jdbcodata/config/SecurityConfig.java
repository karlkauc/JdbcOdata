package at.jdbcodata.config;

import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Three security chains:
 * <ul>
 *   <li>{@code /odata/**}: open for Spring Security. The OData servlet reads the Basic Auth header itself and
 *       forwards the credentials to the target database (pass-through). Nothing is validated here.</li>
 *   <li>{@code /api/**}: stateless Basic Auth against the admin account, no CSRF.</li>
 *   <li>everything else (admin UI, actuator): session login form (Basic Auth also accepted), CSRF enabled.</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    @Order(1)
    SecurityFilterChain odataChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/odata/**")
                .authorizeHttpRequests(a -> a.anyRequest().permitAll())
                .csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**")
                .authorizeHttpRequests(a -> a.anyRequest().hasRole("ADMIN"))
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
                                "/webjars/**", "/css/**", "/js/**").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .formLogin(f -> f.loginPage("/login").defaultSuccessUrl("/admin", false).permitAll())
                .logout(l -> l.logoutUrl("/logout").logoutSuccessUrl("/login?logout"))
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    UserDetailsService userDetailsService(JdbcOdataProperties props, PasswordEncoder encoder) {
        String password = props.admin().password();
        if (password == null || password.isBlank()) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("No admin password configured (jdbcodata.admin.password / JDBCODATA_ADMIN_PASSWORD). "
                    + "Generated password for admin user '{}' for this run: {}", props.admin().user(), password);
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(props.admin().user()).password(encoder.encode(password)).roles("ADMIN").build());
    }
}
