package at.jdbcodata.admin.account;

import at.jdbcodata.common.SecretFiles;
import at.jdbcodata.config.JdbcOdataProperties;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/** Creates the first admin account from configuration when the table is empty (PRD FR-65). */
@Component
@Order(0)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);
    static final String INITIAL_PASSWORD_FILE = "initial-admin-password";
    private static final int MIN_PASSWORD_LENGTH = 8;

    private final AdminUserService service;
    private final JdbcOdataProperties props;
    private final Environment environment;

    public AdminBootstrap(AdminUserService service, JdbcOdataProperties props, Environment environment) {
        this.service = service;
        this.props = props;
        this.environment = environment;
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
        if (!generated && password.toLowerCase().startsWith("change-me")) {
            throw new IllegalStateException("jdbcodata.admin.password is still the placeholder from an example file; "
                    + "set a real password or leave it empty for a generated one");
        }
        if (!generated && password.length() < MIN_PASSWORD_LENGTH && !environment.acceptsProfiles(Profiles.of("demo"))) {
            throw new IllegalStateException("jdbcodata.admin.password must have at least " + MIN_PASSWORD_LENGTH
                    + " characters; set a longer one or leave it empty for a generated one");
        }
        service.create(new AdminUserService.AdminUserSpec(props.admin().user(), password, AdminRole.ADMIN, true), false, generated);
        if (generated) {
            // never in the log: logs end up in log collectors and support tickets
            Path file = Path.of(props.dataDir(), INITIAL_PASSWORD_FILE);
            try {
                Files.deleteIfExists(file); // left over from an earlier installation, no longer valid
                SecretFiles.createOwnerOnly(file, password + System.lineSeparator());
                log.warn("No admin password configured (jdbcodata.admin.password / JDBCODATA_ADMIN_PASSWORD). "
                        + "Created admin user '{}'; the generated password is in {} (delete it after the first login)",
                        props.admin().user(), file.toAbsolutePath());
            } catch (IOException e) {
                throw new IllegalStateException("Cannot write the generated admin password to " + file
                        + "; set jdbcodata.admin.password instead", e);
            }
        } else {
            log.info("Created initial admin user '{}'", props.admin().user());
        }
    }
}
