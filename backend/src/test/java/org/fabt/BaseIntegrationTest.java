package org.fabt;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import org.junit.jupiter.api.AfterEach;
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
    protected static final GreenMail GREEN_MAIL;

    static {
        POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("fabt_test")
                .withUsername("fabt_test")
                .withPassword("fabt_test")
                .withCommand("postgres -c max_connections=200");
        POSTGRES.start();

        // Embedded SMTP server for email integration tests (no Docker needed)
        GREEN_MAIL = new GreenMail(new ServerSetup(0, "localhost", ServerSetup.PROTOCOL_SMTP));
        GREEN_MAIL.start();
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
        // Encryption key for TOTP + webhook secrets (SecretEncryptionService reads fabt.encryption-key with fabt.totp.encryption-key fallback)
        registry.add("fabt.totp.encryption-key", () -> "dGVzdC1vbmx5LXRvdHAtZW5jcnlwdGlvbi1rZXktMzI=");
        // GreenMail embedded SMTP — enables EmailService bean (@ConditionalOnProperty spring.mail.host)
        registry.add("spring.mail.host", () -> "localhost");
        registry.add("spring.mail.port", () -> String.valueOf(GREEN_MAIL.getSmtp().getPort()));
        // API key rate limit: default 5/min applies. Tests that make >5 API key requests
        // should inject ApiKeyAuthenticationFilter and call rateLimitBuckets.invalidateAll()
        // in @BeforeEach, or use @SpringBootTest(properties = "fabt.api-key.rate-limit=1000").
        //
        // SSRF guard (D12): the base intentionally does NOT loosen
        // SafeOutboundUrlValidator. Production-faithful default keeps the SSRF
        // rejection contract (cloud-metadata, loopback, RFC1918) live in every
        // integration test. The two tests that need a localhost WireMock mock
        // (WebhookTestEventDeliveryTest, WebhookTimeoutTest) replace the
        // validator with a Mockito stub via @MockitoBean — the production
        // validator stays armed for everything else.
    }

    @Autowired
    protected TestRestTemplate restTemplate;

    /**
     * Per-test GreenMail purge — the canonical pattern per GreenMail docs
     * for shared-singleton-across-classes setups. Without this, the
     * {@code getReceivedMessages()} array accumulates across the entire JVM
     * run, and the JUnit 5 default {@code MethodOrderer} (deterministic but
     * intentionally non-obvious) can re-order tests when methods are
     * added/removed — making any "messages since baseline index" pattern
     * fragile.
     *
     * <p>Established 2026-04-15 after a test-ordering reshuffle caused
     * {@code EmailPasswordResetIntegrationTest.happyPath} to read another
     * test's residual email via the (now-removed) {@code greenMailBaseline}
     * pattern. Fix per
     * <a href="https://github.com/greenmail-mail-test/greenmail/blob/master/greenmail-core/src/test/java/com/icegreen/greenmail/examples/ExamplePurgeAllEmailsTest.java">
     * GreenMail's own ExamplePurgeAllEmailsTest</a>.
     */
    @AfterEach
    void purgeGreenMailMailboxes() throws Exception {
        GREEN_MAIL.purgeEmailFromAllMailboxes();
    }
}
