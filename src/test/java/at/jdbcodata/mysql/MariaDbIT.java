package at.jdbcodata.mysql;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MariaDbIT extends MysqlFamilyIT {

    @Container
    static GenericContainer<?> database = server("mariadb:11.4");

    @Override
    GenericContainer<?> database() {
        return database;
    }
}
