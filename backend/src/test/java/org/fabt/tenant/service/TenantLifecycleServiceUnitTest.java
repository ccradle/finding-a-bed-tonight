package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.fabt.tenant.domain.IllegalStateTransitionException;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link TenantLifecycleService}'s private {@code transitionTo} contract,
 * exercised via the public suspend/unsuspend/offboard/archive methods. Covers the two
 * failure branches (tenant-not-found, forbidden-transition) and the happy-path shape
 * (load → assert → flip → save, no other side effects) before F-2 layers JWT bump +
 * audit emission on top.
 *
 * <p>Scope deliberately narrow: the FSM matrix itself is covered by
 * {@link org.fabt.tenant.domain.TenantStateTransitionTest}; the DB round-trip is
 * covered by {@code TenantIntegrationTest}. This file is the service-level contract.</p>
 */
@ExtendWith(MockitoExtension.class)
class TenantLifecycleServiceUnitTest {

    @Mock
    TenantRepository tenantRepository;

    private TenantLifecycleService newService() {
        return new TenantLifecycleService(tenantRepository);
    }

    // ─── Failure branch 1: tenant-not-found ──────────────────────────────────

    @Test
    void suspend_unknownTenant_throwsNoSuchElementExceptionReferencingId() {
        UUID unknown = UUID.randomUUID();
        when(tenantRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().suspend(unknown, UUID.randomUUID(), "op"))
            .isInstanceOf(NoSuchElementException.class)
            .hasMessageContaining(unknown.toString());

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void unsuspend_unknownTenant_throwsNoSuchElementException() {
        UUID unknown = UUID.randomUUID();
        when(tenantRepository.findById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> newService().unsuspend(unknown, UUID.randomUUID(), "op"))
            .isInstanceOf(NoSuchElementException.class);

        verify(tenantRepository, never()).save(any());
    }

    // ─── Failure branch 2: forbidden transition ─────────────────────────────

    @Test
    void unsuspend_activeTenant_throwsIllegalStateTransition_noSave() {
        // ACTIVE -> ACTIVE is a self-transition, disallowed by §D8
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> newService().unsuspend(active.getId(), UUID.randomUUID(), "op"))
            .isInstanceOf(IllegalStateTransitionException.class);

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void suspend_archivedTenant_throwsIllegalStateTransition_noSave() {
        // ARCHIVED -> SUSPENDED is not a permitted §D8 transition
        Tenant archived = newTenant(TenantState.ARCHIVED);
        when(tenantRepository.findById(archived.getId())).thenReturn(Optional.of(archived));

        assertThatThrownBy(() -> newService().suspend(archived.getId(), UUID.randomUUID(), "op"))
            .isInstanceOf(IllegalStateTransitionException.class);

        verify(tenantRepository, never()).save(any());
    }

    @Test
    void archive_activeTenant_throwsIllegalStateTransition_noSave() {
        // ACTIVE -> ARCHIVED must go through OFFBOARDING first per §D8
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> newService().archive(active.getId(), UUID.randomUUID(), "op"))
            .isInstanceOf(IllegalStateTransitionException.class);

        verify(tenantRepository, never()).save(any());
    }

    // ─── Happy path: load → assert → flip → save ────────────────────────────

    @Test
    void suspend_activeTenant_flipsStateAndSavesExactlyOnce() {
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = newService().suspend(active.getId(), UUID.randomUUID(), "quarantine");

        assertThat(result.getState()).isEqualTo(TenantState.SUSPENDED);
        assertThat(active.getState())
            .as("same instance mutated then saved")
            .isEqualTo(TenantState.SUSPENDED);
        verify(tenantRepository, times(1)).save(active);
    }

    @Test
    void unsuspend_suspendedTenant_flipsStateAndSaves() {
        Tenant suspended = newTenant(TenantState.SUSPENDED);
        when(tenantRepository.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = newService().unsuspend(suspended.getId(), UUID.randomUUID(), "cleared");

        assertThat(result.getState()).isEqualTo(TenantState.ACTIVE);
        verify(tenantRepository, times(1)).save(suspended);
    }

    @Test
    void offboard_fromActiveOrSuspended_flipsToOffboarding() {
        for (TenantState start : new TenantState[]{TenantState.ACTIVE, TenantState.SUSPENDED}) {
            Tenant t = newTenant(start);
            when(tenantRepository.findById(t.getId())).thenReturn(Optional.of(t));
            when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

            Tenant result = newService().offboard(t.getId(), UUID.randomUUID(), "tenant-request");

            assertThat(result.getState())
                .as("offboard from %s", start)
                .isEqualTo(TenantState.OFFBOARDING);
        }
    }

    @Test
    void archive_fromOffboarding_flipsToArchived() {
        Tenant t = newTenant(TenantState.OFFBOARDING);
        when(tenantRepository.findById(t.getId())).thenReturn(Optional.of(t));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = newService().archive(t.getId(), UUID.randomUUID(), "export-complete");

        assertThat(result.getState()).isEqualTo(TenantState.ARCHIVED);
    }

    // ─── Stub methods (F-1 scope: create + hardDelete intentionally deferred) ─

    @Test
    void create_throwsUnsupportedOperation_deferredToF4() {
        assertThatThrownBy(() ->
                newService().create("Demo", "demo-slug", UUID.randomUUID()))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("F-4");
    }

    @Test
    void hardDelete_throwsUnsupportedOperation_deferredToF6() {
        assertThatThrownBy(() ->
                newService().hardDelete(UUID.randomUUID(), UUID.randomUUID(), "retention-complete"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("F-6");
    }

    // ─── Fixture helper ──────────────────────────────────────────────────────

    private static Tenant newTenant(TenantState state) {
        Tenant t = new Tenant();
        t.setId(UUID.randomUUID());
        t.setName("Test Tenant");
        t.setSlug("test-tenant-" + UUID.randomUUID());
        t.setState(state);
        return t;
    }
}
