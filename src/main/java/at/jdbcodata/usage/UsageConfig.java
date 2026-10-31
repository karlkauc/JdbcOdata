package at.jdbcodata.usage;

import at.jdbcodata.query.QueryService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/** Active only with {@code jdbcodata.usage.db-url}: self-hosted installations record and send nothing. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnExpression("!'${jdbcodata.usage.db-url:}'.isBlank()")
@EnableConfigurationProperties(UsageProperties.class)
public class UsageConfig {

    @Bean(initMethod = "start", destroyMethod = "stop")
    UsageRecorder usageRecorder(UsageProperties props) {
        if (props.hashSecret().isBlank()) {
            throw new IllegalStateException("jdbcodata.usage.hash-secret is required with jdbcodata.usage.db-url");
        }
        return new UsageRecorder(props);
    }

    /** First in the chain, so a request refused by the rate limit or by Spring Security is counted as well. */
    @Bean
    FilterRegistrationBean<UsageFilter> usageFilter(UsageRecorder recorder, UsageProperties props, QueryService queries) {
        String version = UsageConfig.class.getPackage().getImplementationVersion();
        FilterRegistrationBean<UsageFilter> bean = new FilterRegistrationBean<>(new UsageFilter(recorder,
                new VisitorHasher(props.hashSecret()), new PublishedQueryNames(queries), Clock.systemUTC(),
                version == null ? "dev" : version));
        bean.addUrlPatterns("/odata", "/odata/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 5);
        return bean;
    }
}
