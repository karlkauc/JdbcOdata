package at.jdbcodata;

import at.jdbcodata.config.JdbcOdataProperties;
import at.jdbcodata.security.OidcProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({JdbcOdataProperties.class, OidcProperties.class})
public class JdbcOdataApplication {

    public static void main(String[] args) {
        SpringApplication.run(JdbcOdataApplication.class, args);
    }
}
