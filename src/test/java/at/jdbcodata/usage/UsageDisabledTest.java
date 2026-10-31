package at.jdbcodata.usage;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/** The default of every self-hosted installation: without these beans no thread starts and no connection opens. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:meta-usage-off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "jdbcodata.demo.jdbc-url=jdbc:h2:mem:demo-usage-off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.profiles.active=demo",
        "jdbcodata.admin.user=admin",
        "jdbcodata.admin.password=secret",
        "jdbcodata.stats.async=false",
        "jdbcodata.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "jdbcodata.cache.cleanup-interval-ms=3600000",
        "jdbcodata.security.rate-limit-per-minute=100000"
})
class UsageDisabledTest {

    @Autowired
    ApplicationContext context;

    @Test
    void offWithoutDbUrl() {
        assertThat(context.getBeanNamesForType(UsageRecorder.class)).isEmpty();
        assertThat(context.getBeanNamesForType(UsageConfig.class)).isEmpty();
    }
}
