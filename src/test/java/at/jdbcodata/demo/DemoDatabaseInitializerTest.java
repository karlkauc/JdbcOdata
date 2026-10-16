package at.jdbcodata.demo;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;

class DemoDatabaseInitializerTest {

    @Test
    void createsDataOnceAndIsIdempotent() throws Exception {
        String url = "jdbc:h2:mem:demo-init;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        DemoDatabaseInitializer init = new DemoDatabaseInitializer(url);
        init.run(null);
        init.run(null);
        try (Connection c = DriverManager.getConnection(url, DemoDatabaseInitializer.USER, DemoDatabaseInitializer.PASSWORD);
             ResultSet rs = c.createStatement().executeQuery("SELECT COUNT(*), SUM(CASE WHEN sale_year = 2025 THEN 1 ELSE 0 END) FROM sales")) {
            rs.next();
            assertThat(rs.getInt(1)).isEqualTo(36);
            assertThat(rs.getInt(2)).isEqualTo(24);
        }
    }
}
