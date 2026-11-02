package at.jdbcodata.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * {@code JDBCODATA_MASTER_KEY_FILE=/run/secrets/key} sets {@code JDBCODATA_MASTER_KEY} to the content of that file, the
 * convention of Docker secrets. Works for every {@code JDBCODATA_*} and {@code SPRING_*} variable, so a secret need not
 * be in the environment of the process.
 */
public class SecretFileEnvironment implements EnvironmentPostProcessor {

    private static final String SUFFIX = "_FILE";
    /** A setting of its own that happens to end in the suffix. */
    private static final Set<String> NOT_SECRETS = Set.of("JDBCODATA_SEED_FILE");

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> variables = environment.getSystemEnvironment();
        Map<String, Object> secrets = new HashMap<>();
        variables.forEach((name, value) -> {
            if (!isSecretFile(name)) return;
            String target = name.substring(0, name.length() - SUFFIX.length());
            if (variables.get(target) instanceof String direct && !direct.isBlank()) {
                throw new IllegalStateException("Set either " + target + " or " + name + ", not both");
            }
            secrets.put(target, read(name, Path.of(String.valueOf(value))));
        });
        if (secrets.isEmpty()) return;
        // this name makes Spring map jdbcodata.master-key to JDBCODATA_MASTER_KEY, as for real variables
        environment.getPropertySources().addBefore(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("secretFiles-" + StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, secrets));
    }

    private static boolean isSecretFile(String name) {
        return name.endsWith(SUFFIX) && !NOT_SECRETS.contains(name)
                && (name.startsWith("JDBCODATA_") || name.startsWith("SPRING_"));
    }

    private static String read(String name, Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + file + " from " + name, e);
        }
    }
}
