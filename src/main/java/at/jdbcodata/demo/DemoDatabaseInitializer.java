package at.jdbcodata.demo;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Profile {@code demo}: creates a local H2 database owned by user {@code demo}/{@code demo} with a {@code sales}
 * table, so the feed can be tried in Excel without any company database.
 */
@Component
@Profile("demo")
@Order(1)
public class DemoDatabaseInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDatabaseInitializer.class);
    public static final String USER = "demo";
    public static final String PASSWORD = "demo";

    private final String jdbcUrl;

    public DemoDatabaseInitializer(@Value("${jdbcodata.demo.jdbc-url}") String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException {
        try (Connection c = DriverManager.getConnection(jdbcUrl, USER, PASSWORD)) {
            try (Statement st = c.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sales (
                            id         INTEGER PRIMARY KEY,
                            customer   VARCHAR(100) NOT NULL,
                            region     VARCHAR(50),
                            sale_year  INTEGER NOT NULL,
                            sale_month INTEGER NOT NULL,
                            amount     NUMERIC(12,2) NOT NULL,
                            sold_at    TIMESTAMP NOT NULL,
                            delivered  BOOLEAN NOT NULL
                        )""");
            }
            if (countRows(c) == 0) {
                insertSampleData(c);
                log.info("Demo database {} created with sample data (user {} / password {})", jdbcUrl, USER, PASSWORD);
            } else {
                log.info("Demo database {} ready (user {} / password {})", jdbcUrl, USER, PASSWORD);
            }
        }
    }

    private static long countRows(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sales")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static void insertSampleData(Connection c) throws SQLException {
        String[] customers = {"Alpha GmbH", "Beta AG"};
        String[] regions = {"Nord", "Süd"};
        int id = 1;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sales (id, customer, region, sale_year, sale_month, amount, sold_at, delivered) VALUES (?,?,?,?,?,?,?,?)")) {
            for (int year : new int[] {2024, 2025}) {
                for (int ci = 0; ci < customers.length; ci++) {
                    int months = year == 2024 ? 6 : 12;
                    for (int month = 1; month <= months; month++) {
                        ps.setInt(1, id);
                        ps.setString(2, customers[ci]);
                        ps.setString(3, regions[ci]);
                        ps.setInt(4, year);
                        ps.setInt(5, month);
                        ps.setBigDecimal(6, BigDecimal.valueOf(1000 + (id * 137L) % 9000).add(new BigDecimal("0.50")));
                        ps.setTimestamp(7, Timestamp.valueOf(LocalDateTime.of(year, month, Math.min(id, 28), 9, 30)));
                        ps.setBoolean(8, id % 3 != 0);
                        ps.addBatch();
                        id++;
                    }
                }
            }
            ps.executeBatch();
        }
        c.commit();
    }
}
