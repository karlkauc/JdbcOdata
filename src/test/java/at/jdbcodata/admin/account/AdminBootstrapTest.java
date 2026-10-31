package at.jdbcodata.admin.account;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import at.jdbcodata.config.JdbcOdataProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AdminBootstrapTest {

    private final AdminUserService service = mock(AdminUserService.class);
    private final JdbcOdataProperties props = mock(JdbcOdataProperties.class);
    private final MockEnvironment environment = new MockEnvironment();

    @Test
    void shortPasswordStopsTheStart() {
        when(props.admin()).thenReturn(new JdbcOdataProperties.Admin("admin", "short"));
        assertThatThrownBy(() -> new AdminBootstrap(service, props, environment).run(null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("at least 8");
    }

    @Test
    void demoProfileAcceptsShortPasswords() {
        when(props.admin()).thenReturn(new JdbcOdataProperties.Admin("admin", "short"));
        environment.setActiveProfiles("demo");
        assertThatCode(() -> new AdminBootstrap(service, props, environment).run(null)).doesNotThrowAnyException();
    }
}
