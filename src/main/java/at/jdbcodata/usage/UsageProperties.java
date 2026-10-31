package at.jdbcodata.usage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Usage statistics of a public instance, written to a PostgreSQL database of its own. Without {@code dbUrl} nothing
 * is recorded: no thread, no connection. {@code hashSecret} keys the daily salt of the visitor hash.
 */
@ConfigurationProperties(prefix = "jdbcodata.usage")
public record UsageProperties(@DefaultValue("") String dbUrl, @DefaultValue("") String dbUser,
                              @DefaultValue("") String dbPassword, @DefaultValue("") String hashSecret) {
}
