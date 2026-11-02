package at.jdbcodata.cluster;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

/**
 * Lets one instance at a time run the startup work (first admin, re-encryption, seed): each of those checks and then
 * writes, so instances starting together would collide on unique constraints and fail. Held from the first to the last
 * {@link ApplicationRunner} in a transaction of its own; if a runner fails, closing the context releases the lock.
 */
@Configuration
public class StartupLock implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(StartupLock.class);

    private final DataSource dataSource;
    private Connection held;

    public StartupLock(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    ApplicationRunner acquireStartupLock() {
        return args -> acquire();
    }

    @Bean
    @Order(Ordered.LOWEST_PRECEDENCE)
    ApplicationRunner releaseStartupLock() {
        return args -> release();
    }

    private void acquire() throws SQLException {
        log.info("Waiting for other instances to finish their startup work");
        Connection connection = dataSource.getConnection();
        try (PreparedStatement lock = connection.prepareStatement("SELECT name FROM cluster_lock WHERE name = 'startup' FOR UPDATE")) {
            connection.setAutoCommit(false);
            try (ResultSet rs = lock.executeQuery()) {
                if (!rs.next()) throw new IllegalStateException("Row 'startup' is missing in cluster_lock");
            }
            held = connection;
        } catch (SQLException | RuntimeException e) {
            connection.close();
            throw e;
        }
    }

    private void release() {
        if (held == null) return;
        try (Connection connection = held) {
            connection.rollback();
        } catch (SQLException e) {
            // the database dropped the connection, and the lock with it
            log.warn("Could not release the startup lock cleanly: {}", e.getMessage());
        }
        held = null;
    }

    @Override
    public void destroy() {
        release();
    }
}
