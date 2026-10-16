package at.jdbcodata.admin.account;

import at.jdbcodata.config.JdbcOdataProperties;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Creates the first admin account from configuration when the table is empty (PRD FR-65). */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AdminUserService service;
    private final JdbcOdataProperties props;

    public AdminBootstrap(AdminUserService service, JdbcOdataProperties props) {
        this.service = service;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (service.count() > 0) {
            return;
        }
        String password = props.admin().password();
        boolean generated = password == null || password.isBlank();
        if (generated) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
        if (!generated && password.length() < 8) {
            log.warn("The configured admin password is shorter than 8 characters; consider a longer one");
        }
        service.create(new AdminUserService.AdminUserSpec(props.admin().user(), password, AdminRole.ADMIN, true), false);
        if (generated) {
            log.warn("No admin password configured (jdbcodata.admin.password / JDBCODATA_ADMIN_PASSWORD). "
                    + "Created admin user '{}' with generated password: {}", props.admin().user(), password);
        } else {
            log.info("Created initial admin user '{}'", props.admin().user());
        }
    }
}
