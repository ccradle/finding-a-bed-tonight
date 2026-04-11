package org.fabt.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.fabt.notification.domain.EscalationPolicy;
import org.fabt.notification.repository.EscalationPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-22 — Unit tests for {@link EscalationPolicyService}.
 *
 * <p>Pure-mock unit test (no Spring context, no Testcontainer). Exercises:</p>
 * <ul>
 *   <li>Threshold validation: id format, monotonic ordering, severity, roles, recipients</li>
 *   <li>Cache hit / miss / invalidation behaviour</li>
 *   <li>Fallback to platform default when a tenant has no custom policy</li>
 * </ul>
 *
 * <p>Riley Cho's principle: validation must live at the service layer, not the
 * controller, so direct service calls (this test) cannot bypass it.</p>
 */
class EscalationPolicyServiceTest {

    private EscalationPolicyRepository repository;
    private EscalationPolicyService service;

    @BeforeEach
    void setUp() {
        repository = mock(EscalationPolicyRepository.class);
        service = new EscalationPolicyService(repository);
    }

    private static EscalationPolicy.Threshold threshold(String id, Duration at, String severity, String... roles) {
        return new EscalationPolicy.Threshold(id, at, severity, List.of(roles));
    }

    private static EscalationPolicy validPolicy() {
        return new EscalationPolicy(
                UUID.randomUUID(), null, "dv-referral", 1,
                List.of(
                        threshold("1h",   Duration.ofHours(1),    "ACTION_REQUIRED", "COORDINATOR"),
                        threshold("2h",   Duration.ofHours(2),    "CRITICAL",        "COC_ADMIN"),
                        threshold("3_5h", Duration.ofMinutes(210), "CRITICAL",       "COORDINATOR", "OUTREACH_WORKER"),
                        threshold("4h",   Duration.ofHours(4),    "ACTION_REQUIRED", "OUTREACH_WORKER")),
                Instant.now(), null);
    }

    // -------------------------------------------------------------------------
    // Validation
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("validateThresholds")
    class Validation {

        @Test
        @DisplayName("accepts the platform default policy shape")
        void acceptsValidPolicy() {
            service.validateThresholds(validPolicy().thresholds());
            // no exception
        }

        @Test
        @DisplayName("rejects null threshold list")
        void rejectsNullList() {
            assertThatThrownBy(() -> service.validateThresholds(null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one threshold");
        }

        @Test
        @DisplayName("rejects empty threshold list")
        void rejectsEmptyList() {
            assertThatThrownBy(() -> service.validateThresholds(List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least one threshold");
        }

        @Test
        @DisplayName("rejects null id")
        void rejectsNullId() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold(null, Duration.ofHours(1), "INFO", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("id");
        }

        @Test
        @DisplayName("rejects id with uppercase letters or punctuation")
        void rejectsBadIdShape() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("Hour-1", Duration.ofHours(1), "INFO", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects duplicate ids in same policy")
        void rejectsDuplicateIds() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "INFO", "COORDINATOR"),
                    threshold("1h", Duration.ofHours(2), "INFO", "COC_ADMIN"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("duplicated");
        }

        @Test
        @DisplayName("rejects non-monotonic durations")
        void rejectsNonMonotonic() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(2), "INFO", "COORDINATOR"),
                    threshold("2h", Duration.ofHours(1), "INFO", "COC_ADMIN"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("monotonically increasing");
        }

        @Test
        @DisplayName("rejects identical durations (must be strictly greater)")
        void rejectsEqualDurations() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("a", Duration.ofHours(1), "INFO", "COORDINATOR"),
                    threshold("b", Duration.ofHours(1), "INFO", "COC_ADMIN"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("strictly greater");
        }

        @Test
        @DisplayName("rejects zero or negative duration")
        void rejectsNonPositive() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("z", Duration.ZERO, "INFO", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("positive");
        }

        @Test
        @DisplayName("rejects unknown severity")
        void rejectsBadSeverity() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "URGENT_ISH", "COORDINATOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("severity");
        }

        @Test
        @DisplayName("rejects empty recipients")
        void rejectsEmptyRecipients() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "INFO"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("recipients");
        }

        @Test
        @DisplayName("rejects unknown recipient role")
        void rejectsBadRole() {
            assertThatThrownBy(() -> service.validateThresholds(List.of(
                    threshold("1h", Duration.ofHours(1), "INFO", "JANITOR"))))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("JANITOR");
        }
    }

    // -------------------------------------------------------------------------
    // Caching + fallback
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("findById caches subsequent lookups")
    void findByIdCaches() {
        EscalationPolicy p = validPolicy();
        when(repository.findById(p.id())).thenReturn(java.util.Optional.of(p));

        assertThat(service.findById(p.id())).contains(p);
        assertThat(service.findById(p.id())).contains(p);
        assertThat(service.findById(p.id())).contains(p);

        verify(repository, times(1)).findById(p.id());
    }

    @Test
    @DisplayName("findById returns empty for null id without hitting repository")
    void findByIdNullSafe() {
        assertThat(service.findById(null)).isEmpty();
        verify(repository, never()).findById(any());
    }

    @Test
    @DisplayName("getCurrentForTenant falls back to platform default when tenant has none")
    void fallbackToPlatformDefault() {
        UUID tenantId = UUID.randomUUID();
        EscalationPolicy platformDefault = new EscalationPolicy(
                UUID.randomUUID(), null, "dv-referral", 1,
                validPolicy().thresholds(), Instant.now(), null);
        when(repository.findCurrentByTenantAndEventType(tenantId, "dv-referral"))
                .thenReturn(java.util.Optional.empty());
        when(repository.findCurrentPlatformDefault("dv-referral"))
                .thenReturn(java.util.Optional.of(platformDefault));

        var resolved = service.getCurrentForTenant(tenantId, "dv-referral");
        assertThat(resolved).contains(platformDefault);

        // Second call must hit cache, not repository.
        assertThat(service.getCurrentForTenant(tenantId, "dv-referral")).contains(platformDefault);
        verify(repository, times(1)).findCurrentByTenantAndEventType(tenantId, "dv-referral");
        verify(repository, times(1)).findCurrentPlatformDefault("dv-referral");
    }

    @Test
    @DisplayName("update inserts a new version, invalidates cache, returns the new policy")
    void updateInsertsAndInvalidates() {
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        EscalationPolicy v1 = new EscalationPolicy(
                UUID.randomUUID(), tenantId, "dv-referral", 1,
                validPolicy().thresholds(), Instant.now(), actor);
        EscalationPolicy v2 = new EscalationPolicy(
                UUID.randomUUID(), tenantId, "dv-referral", 2,
                validPolicy().thresholds(), Instant.now(), actor);

        // Prime the tenant cache with v1.
        when(repository.findCurrentByTenantAndEventType(tenantId, "dv-referral"))
                .thenReturn(java.util.Optional.of(v1));
        assertThat(service.getCurrentForTenant(tenantId, "dv-referral")).contains(v1);

        // Update returns v2 — cache must invalidate so the next read sees v2.
        when(repository.insertNewVersion(eq(tenantId), eq("dv-referral"), any(), eq(actor)))
                .thenReturn(v2);
        when(repository.findCurrentByTenantAndEventType(tenantId, "dv-referral"))
                .thenReturn(java.util.Optional.of(v2));

        EscalationPolicy returned = service.update(tenantId, "dv-referral", v2.thresholds(), actor);
        assertThat(returned).isEqualTo(v2);

        assertThat(service.getCurrentForTenant(tenantId, "dv-referral")).contains(v2);
        // findById should be cached for v2 because update() pre-populates it.
        assertThat(service.findById(v2.id())).contains(v2);
        verify(repository, never()).findById(v2.id());
    }

    @Test
    @DisplayName("update rejects invalid policy without inserting")
    void updateRejectsInvalid() {
        UUID tenantId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> service.update(tenantId, "dv-referral",
                List.of(threshold("z", Duration.ZERO, "INFO", "COORDINATOR")), actor))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository, never()).insertNewVersion(any(), anyString(), any(), any());
    }
}
