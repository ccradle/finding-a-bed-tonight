package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.domain.TenantStateGuardException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Unit tests for {@link TenantStateGuard}. Pins the F-3 read-path contract:
 * SUSPENDED/OFFBOARDING/ARCHIVED/DELETED tenants are rejected with
 * {@link TenantStateGuardException} carrying the correct {@code Kind}; ACTIVE
 * tenants pass through; NOT_FOUND uses the same-shape exception for
 * existence-leak symmetry. Also pins cache behavior: repeated reads hit the
 * cache (one DB call for many {@code requireActive}s on the same tenant) and
 * {@link TenantStateGuard#invalidate} forces a re-load on the next call.
 */
@ExtendWith(MockitoExtension.class)
class TenantStateGuardUnitTest {

    @Mock
    JdbcTemplate jdbc;

    @Test
    void requireActive_activeTenant_returnsNormally() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);
        UUID tenantId = UUID.randomUUID();
        stubLoad(tenantId, "ACTIVE");

        guard.requireActive(tenantId);

        verify(jdbc, times(1)).queryForObject(anyString(), eq(String.class), eq(tenantId));
    }

    @Test
    void requireActive_suspendedTenant_throwsNonActiveWithObservedState() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);
        UUID tenantId = UUID.randomUUID();
        stubLoad(tenantId, "SUSPENDED");

        assertThatThrownBy(() -> guard.requireActive(tenantId))
            .isInstanceOf(TenantStateGuardException.class)
            .satisfies(ex -> {
                TenantStateGuardException typed = (TenantStateGuardException) ex;
                assertThat(typed.kind()).isEqualTo(TenantStateGuardException.Kind.NON_ACTIVE);
                assertThat(typed.tenantId()).isEqualTo(tenantId);
                assertThat(typed.observedState()).isEqualTo(TenantState.SUSPENDED);
            });
    }

    @Test
    void requireActive_tenantNotFound_throwsNotFoundKind() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);
        UUID tenantId = UUID.randomUUID();
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(tenantId)))
            .thenThrow(new EmptyResultDataAccessException(1));

        assertThatThrownBy(() -> guard.requireActive(tenantId))
            .isInstanceOf(TenantStateGuardException.class)
            .satisfies(ex -> {
                TenantStateGuardException typed = (TenantStateGuardException) ex;
                assertThat(typed.kind()).isEqualTo(TenantStateGuardException.Kind.NOT_FOUND);
                assertThat(typed.tenantId()).isEqualTo(tenantId);
                assertThat(typed.observedState()).isNull();
            });
    }

    @Test
    void requireActive_nullTenantId_throwsNotFound_withoutDbCall() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);

        assertThatThrownBy(() -> guard.requireActive(null))
            .isInstanceOf(TenantStateGuardException.class)
            .satisfies(ex -> assertThat(((TenantStateGuardException) ex).kind())
                .isEqualTo(TenantStateGuardException.Kind.NOT_FOUND));

        verify(jdbc, never()).queryForObject(anyString(), any(Class.class), any(Object[].class));
    }

    @Test
    void requireActive_cachedHit_doesNotHitDbOnRepeatCall() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);
        UUID tenantId = UUID.randomUUID();
        stubLoad(tenantId, "ACTIVE");

        guard.requireActive(tenantId);
        guard.requireActive(tenantId);
        guard.requireActive(tenantId);

        // One DB call for three requireActive invocations — cache absorbed two.
        verify(jdbc, times(1)).queryForObject(anyString(), eq(String.class), eq(tenantId));
    }

    @Test
    void invalidate_forcesReloadOnNextRequireActive() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);
        UUID tenantId = UUID.randomUUID();
        stubLoad(tenantId, "ACTIVE");

        guard.requireActive(tenantId);   // first load
        guard.invalidate(tenantId);      // cache cleared
        guard.requireActive(tenantId);   // forced re-load

        verify(jdbc, times(2)).queryForObject(anyString(), eq(String.class), eq(tenantId));
    }

    @Test
    void invalidate_nullTenantId_noThrow() {
        TenantStateGuard guard = new TenantStateGuard(jdbc);

        // Defensive — lifecycle hooks may pass null during tests or edge cases;
        // invalidate must be no-op safe so the after-commit path never throws.
        guard.invalidate(null);

        verify(jdbc, never()).queryForObject(anyString(), any(Class.class), any(Object[].class));
    }

    private void stubLoad(UUID tenantId, String state) {
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(tenantId)))
            .thenReturn(state);
    }
}
