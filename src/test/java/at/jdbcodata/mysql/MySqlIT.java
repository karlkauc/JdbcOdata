package at.jdbcodata.mysql;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class MySqlIT extends MysqlFamilyIT {

    @Container
    static GenericContainer<?> database = server("mysql:8.4");

    @Override
    GenericContainer<?> database() {
        return database;
    }
}
