package at.jdbcodata.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.StandardEnvironment;

class SecretFileEnvironmentTest {

    @TempDir
    Path dir;

    private StandardEnvironment environment(Map<String, Object> variables) {
        StandardEnvironment environment = new StandardEnvironment() {
            @Override
            public Map<String, Object> getSystemEnvironment() {
                return variables;
            }
        };
        new SecretFileEnvironment().postProcessEnvironment(environment, null);
        ConfigurationPropertySources.attach(environment);
        return environment;
    }

    private String secret(String content) throws IOException {
        return Files.writeString(dir.resolve("secret"), content).toString();
    }

    @Test
    void readsTheFileLikeTheVariableItself() throws IOException {
        StandardEnvironment environment = environment(Map.of("JDBCODATA_MASTER_KEY_FILE", secret("from-file\n")));

        assertThat(Binder.get(environment).bind("jdbcodata.master-key", String.class).get()).isEqualTo("from-file");
        assertThat(environment.getProperty("jdbcodata.master-key")).isEqualTo("from-file");
    }

    @Test
    void anEmptyVariableDoesNotHideTheFile() throws IOException {
        Map<String, Object> variables = new HashMap<>();
        variables.put("JDBCODATA_META_PASSWORD", "");
        variables.put("JDBCODATA_META_PASSWORD_FILE", secret("from-file"));

        assertThat(environment(variables).getProperty("jdbcodata.meta.password")).isEqualTo("from-file");
    }

    @Test
    void bothAtOnceIsAMistake() throws IOException {
        Map<String, Object> variables = Map.of("JDBCODATA_MASTER_KEY", "direct", "JDBCODATA_MASTER_KEY_FILE", secret("x"));

        assertThatThrownBy(() -> environment(variables)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JDBCODATA_MASTER_KEY_FILE").hasMessageNotContaining("direct");
    }

    @Test
    void aMissingFileStopsTheStartWithItsName() {
        assertThatThrownBy(() -> environment(Map.of("SPRING_DATASOURCE_PASSWORD_FILE", "/does/not/exist")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("/does/not/exist");
    }

    @Test
    void theSeedFileIsASettingNotASecret() {
        StandardEnvironment environment = environment(Map.of("JDBCODATA_SEED_FILE", "/does/not/exist"));

        assertThat(environment.getProperty("jdbcodata.seed")).isNull();
    }

    @Test
    void otherVariablesStayUntouched() {
        assertThat(environment(Map.of("PATH_FILE", "/does/not/exist")).getProperty("PATH")).isNull();
    }
}
