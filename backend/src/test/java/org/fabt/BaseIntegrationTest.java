package org.fabt;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for integration tests. Uses a singleton Testcontainers PostgreSQL
 * instance shared across ALL test classes to avoid connection pool exhaustion.
 *
 * The container starts once per JVM (static initializer) and is reused by all
 * test classes that extend this base. Spring contexts may restart between test
 * classes but they all connect to the same PostgreSQL instance.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"lite", "test"})
public abstract class BaseIntegrationTest {

    static final PostgreSQLContainer POSTGRES;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("fabt_test")
                .withUsername("fabt_test")
                .withPassword("fabt_test")
                .withCommand("postgres -c max_connections=200");
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        // Keep pool small — multiple Spring test contexts (due to @AutoConfigureMetrics,
        // @TestPropertySource, etc.) each create their own HikariCP pool against the
        // shared Testcontainers PostgreSQL. 4 contexts x 3 connections = 12, well within
        // the 200 max_connections configured on the container. (Spring Boot #31467)
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "2");
        // Flyway uses the same Testcontainers credentials (owner role in test)
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    protected TestRestTemplate restTemplate;
}
