package at.jdbcodata.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SecurityConfigTest {

    @Test
    void generatesPasswordWhenNoneConfigured() {
        SecurityConfig config = new SecurityConfig();
        JdbcOdataProperties props = new JdbcOdataProperties(new JdbcOdataProperties.Admin("root", ""),
                new JdbcOdataProperties.OData(1, 1), new JdbcOdataProperties.Seed(""));
        UserDetailsService uds = config.userDetailsService(props, new BCryptPasswordEncoder());
        UserDetails user = uds.loadUserByUsername("root");
        assertThat(user.getPassword()).startsWith("$2");
        assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
    }

    @Test
    void usesConfiguredPassword() {
        SecurityConfig config = new SecurityConfig();
        JdbcOdataProperties props = new JdbcOdataProperties(new JdbcOdataProperties.Admin("root", "pw"),
                new JdbcOdataProperties.OData(1, 1), new JdbcOdataProperties.Seed(""));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
        UserDetails user = config.userDetailsService(props, encoder).loadUserByUsername("root");
        assertThat(encoder.matches("pw", user.getPassword())).isTrue();
    }
}
