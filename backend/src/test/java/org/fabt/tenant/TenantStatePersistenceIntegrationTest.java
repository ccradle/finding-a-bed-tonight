package org.fabt.tenant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.fabt.BaseIntegrationTest;
import org.fabt.shared.config.JsonString;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.fabt.tenant.service.TenantLifecycleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end persistence test for the V79 {@code tenant.state} VARCHAR+CHECK column.
 * Proves that Spring Data JDBC 4.x's default {@code Enum.name()} conversion flows
 * cleanly through the Postgres VARCHAR(32) column AND that the CHECK constraint
 * rejects out-of-band values at the database layer.
 *
 * <p>The warroom reviewer flagged this as the highest-leverage missing test in F-1:
 * without it, the "Spring converts enums to VARCHAR automatically" claim rests on
 * the migration comment alone. With it, the claim is ground truth.</p>
 *
 * <p>Also asserts that {@link TenantLifecycleService} is NOT wired when the
 * {@code fabt.tenant.lifecycle.enabled} flag is absent (the default). The flag-on
 * case lives in a sibling test class to avoid paying for a second Spring context
 * in the common path.</p>
 */
class TenantStatePersistenceIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantRepository tenantRepository;

    @Autowired
    private ApplicationContext applicationContext;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void everyTenantStateValueRoundTripsThroughVARCHAR() {
        // Each of the 5 enum values must write AND read back identically.
        for (TenantState state : TenantState.values()) {
            Tenant saved = tenantRepository.save(newTenant(state));

            Optional<Tenant> loaded = tenantRepository.findById(saved.getId());

            assertThat(loaded)
                .as("tenant with state=%s must be retrievable", state)
                .isPresent();
            assertThat(loaded.get().getState())
                .as("state must round-trip as %s", state)
                .isEqualTo(state);
        }
    }

    @Test
    void stateUpdateViaRepositoryPersists() {
        Tenant saved = tenantRepository.save(newTenant(TenantState.ACTIVE));

        saved.setState(TenantState.SUSPENDED);
        tenantRepository.save(saved);

        Tenant reloaded = tenantRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getState()).isEqualTo(TenantState.SUSPENDED);
    }

    @Test
    void dbCheckConstraintRejectsValuesOutsideTheAllowedSet() {
        // Bypass the Java enum by writing a raw SQL insert with an invalid state
        // value. The CHECK constraint added in V79 must reject.
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO tenant (id, name, slug, config, state) VALUES (?, ?, ?, '{}'::jsonb, ?)",
                id, "Bad State", "bad-state-" + id, "BOGUS_STATE"))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void dbDefaultForStateIsActiveWhenNotSupplied() {
        // Insert without specifying state. Column default (SET DEFAULT 'ACTIVE' in
        // V79) must kick in.
        UUID id = UUID.randomUUID();
        jdbc.update(
            "INSERT INTO tenant (id, name, slug, config) VALUES (?, ?, ?, '{}'::jsonb)",
            id, "Default State", "default-state-" + id);

        String state = jdbc.queryForObject(
            "SELECT state FROM tenant WHERE id = ?", String.class, id);
        assertThat(state).isEqualTo("ACTIVE");
    }

    @Test
    void tenantLifecycleServiceBeanIsAbsentWhenFeatureFlagOff() {
        // With fabt.tenant.lifecycle.enabled absent from application-test.yml
        // (it is; default matchIfMissing=false), the @ConditionalOnProperty guard
        // must suppress bean creation. An accidental flag-flip in prod config
        // would be caught by this assertion in CI.
        assertThatThrownBy(() -> applicationContext.getBean(TenantLifecycleService.class))
            .isInstanceOf(NoSuchBeanDefinitionException.class);
    }

    private static Tenant newTenant(TenantState state) {
        Tenant t = new Tenant();
        t.setName("Round-trip Tenant " + state);
        // Slug must be unique across the whole test class; embed state + UUID.
        t.setSlug("rt-" + state.name().toLowerCase() + "-" + UUID.randomUUID());
        t.setConfig(JsonString.empty());
        t.setState(state);
        Instant now = Instant.now();
        t.setCreatedAt(now);
        t.setUpdatedAt(now);
        return t;
    }
}
