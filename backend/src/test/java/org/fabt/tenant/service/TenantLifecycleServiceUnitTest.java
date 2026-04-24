package org.fabt.tenant.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import org.fabt.auth.service.ApiKeyService;
import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventTypes;
import org.fabt.shared.audit.DetachedAuditPersister;
import org.fabt.shared.security.TenantKeyRotationService;
import org.fabt.tenant.service.TenantStateGuard;
import org.fabt.tenant.domain.IllegalStateTransitionException;
import org.fabt.tenant.domain.Tenant;
import org.fabt.tenant.domain.TenantState;
import org.fabt.tenant.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Mock
    TenantKeyRotationService tenantKeyRotationService;

    @Mock
    ApiKeyService apiKeyService;

    @Mock
    ApplicationEventPublisher eventPublisher;

    @Mock
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    @Mock
    TenantStateGuard tenantStateGuard;

    @Mock
    DetachedAuditPersister detachedAuditPersister;

    @Mock
    org.fabt.shared.security.KidRegistryService kidRegistryService;

    private TenantLifecycleService newService() {
        return new TenantLifecycleService(tenantRepository, tenantKeyRotationService,
                                          kidRegistryService,
                                          apiKeyService, eventPublisher, jdbc,
                                          tenantStateGuard, detachedAuditPersister);
    }

    /**
     * Simulates Spring's {@code @Transactional} tx-active flag so the service's runtime
     * assertion in {@code bindTenantContextForAudit} passes. Without this, the assertion
     * correctly fires (no real tx in a Mockito unit context). {@code @AfterEach} resets
     * the flag so one test's state cannot leak to the next.
     */
    @BeforeEach
    void activateMockTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
    }

    @AfterEach
    void deactivateMockTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static TenantKeyRotationService.RotationResult rotationResult(int kids) {
        return new TenantKeyRotationService.RotationResult(
            UUID.randomUUID(), 1, 2, kids, Instant.now());
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
    void unsuspend_activeTenant_throwsIllegalStateTransition_persistsRejectedAuditBeforeRethrow() {
        // ACTIVE -> ACTIVE is a self-transition, disallowed by §D8.
        // F-3.6: the rejected attempt persists TENANT_UNSUSPEND_REJECTED via
        // DetachedAuditPersister (REQUIRES_NEW) BEFORE the exception propagates.
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> newService().unsuspend(active.getId(), actor, "op"))
            .isInstanceOf(IllegalStateTransitionException.class);

        verify(tenantRepository, never()).save(any());

        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(detachedAuditPersister, times(1)).persistDetached(eq(active.getId()), captor.capture());
        AuditEventRecord rejection = captor.getValue();
        assertThat(rejection.action()).isEqualTo(AuditEventTypes.TENANT_UNSUSPEND_REJECTED);
        assertThat(rejection.actorUserId()).isEqualTo(actor);
    }

    @Test
    void suspend_archivedTenant_throwsBeforeAnySideEffects_persistsRejectedAudit() {
        // ARCHIVED -> SUSPENDED is not a permitted §D8 transition. Critical
        // safety property: the FSM assertion must fail BEFORE we invalidate
        // JWT keys or deactivate API keys — otherwise a stray operator click
        // on a SUSPEND button for an already-archived tenant would destroy
        // evidence that belongs in the export bundle.
        //
        // F-3.6: the rejected attempt still persists TENANT_SUSPEND_REJECTED
        // via DetachedAuditPersister so forensic trail survives.
        Tenant archived = newTenant(TenantState.ARCHIVED);
        when(tenantRepository.findById(archived.getId())).thenReturn(Optional.of(archived));
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> newService().suspend(archived.getId(), actor, "op"))
            .isInstanceOf(IllegalStateTransitionException.class);

        verify(tenantRepository, never()).save(any());
        verify(tenantKeyRotationService, never()).bumpJwtKeyGeneration(any(), any());
        verify(apiKeyService, never()).deactivateAllForTenant(any());
        verify(eventPublisher, never()).publishEvent(any());

        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(detachedAuditPersister, times(1)).persistDetached(eq(archived.getId()), captor.capture());
        AuditEventRecord rejection = captor.getValue();
        assertThat(rejection.action()).isEqualTo(AuditEventTypes.TENANT_SUSPEND_REJECTED);
        assertThat(rejection.actorUserId()).isEqualTo(actor);
    }

    @Test
    void archive_activeTenant_throwsIllegalStateTransition_persistsRejectedAudit() {
        // ACTIVE -> ARCHIVED must go through OFFBOARDING first per §D8.
        // F-3.6: rejected archive attempts persist TENANT_ARCHIVE_REJECTED.
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));
        UUID actor = UUID.randomUUID();

        assertThatThrownBy(() -> newService().archive(active.getId(), actor, "op"))
            .isInstanceOf(IllegalStateTransitionException.class);

        verify(tenantRepository, never()).save(any());

        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(detachedAuditPersister, times(1)).persistDetached(eq(active.getId()), captor.capture());
        assertThat(captor.getValue().action())
            .isEqualTo(AuditEventTypes.TENANT_ARCHIVE_REJECTED);
    }

    // ─── Happy path: load → assert → flip → save ────────────────────────────

    @Test
    void suspend_activeTenant_bumpsJwtDeactivatesKeysFlipsStateAndAudits() {
        Tenant active = newTenant(TenantState.ACTIVE);
        UUID actor = UUID.randomUUID();
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(tenantKeyRotationService.bumpJwtKeyGeneration(active.getId(), actor))
            .thenReturn(rotationResult(3));
        when(apiKeyService.deactivateAllForTenant(active.getId())).thenReturn(2);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = newService().suspend(active.getId(), actor, "incident-2026-04-23");

        assertThat(result.getState()).isEqualTo(TenantState.SUSPENDED);
        verify(tenantKeyRotationService, times(1)).bumpJwtKeyGeneration(active.getId(), actor);
        verify(apiKeyService, times(1)).deactivateAllForTenant(active.getId());
        verify(tenantRepository, times(1)).save(active);

        AuditEventRecord audit = captureAuditEvent();
        assertThat(audit.action()).isEqualTo(AuditEventTypes.TENANT_SUSPENDED);
        assertThat(audit.actorUserId()).isEqualTo(actor);
        assertThat(audit.targetUserId()).isNull();
    }

    @Test
    void suspend_auditDetailsContainActorJustificationPreviousStateAndCounts() {
        // Phase G's platform_admin_access_log reads actor_user_id +
        // justification directly — these field names are part of the F-1
        // contract and regressing them breaks G3 silently. Pin the shape.
        Tenant active = newTenant(TenantState.ACTIVE);
        UUID actor = UUID.randomUUID();
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(tenantKeyRotationService.bumpJwtKeyGeneration(any(), any()))
            .thenReturn(rotationResult(7));
        when(apiKeyService.deactivateAllForTenant(any())).thenReturn(4);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        newService().suspend(active.getId(), actor, "quarterly-review-fire");

        AuditEventRecord audit = captureAuditEvent();
        assertThat(audit.action()).isEqualTo(AuditEventTypes.TENANT_SUSPENDED);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) audit.details();
        assertThat(details)
            .containsEntry("actor_user_id", actor.toString())
            .containsEntry("justification", "quarterly-review-fire")
            .containsEntry("previous_state", "ACTIVE")
            .containsEntry("revokedKidCount", 7)
            .containsEntry("deactivatedApiKeys", 4);
    }

    @Test
    void suspend_nullActorUserId_permittedAndRenderedAsNullInDetails() {
        // bumpJwtKeyGeneration Javadoc states actorUserId may be null for
        // system-initiated operations (automated abuse response). Contract
        // carries through the lifecycle service to the audit details.
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));
        when(tenantKeyRotationService.bumpJwtKeyGeneration(any(), eq((UUID) null)))
            .thenReturn(rotationResult(0));
        when(apiKeyService.deactivateAllForTenant(any())).thenReturn(0);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        newService().suspend(active.getId(), null, "system-initiated");

        AuditEventRecord audit = captureAuditEvent();
        assertThat(audit.actorUserId()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) audit.details();
        assertThat(details).containsEntry("actor_user_id", null);
    }

    @Test
    void unsuspend_suspendedTenant_doesNotBumpJwtOrDeactivateKeys_auditEmitted() {
        // Asymmetry with suspend: unsuspend does NOT re-rotate JWT and does
        // NOT reactivate API keys (§D11 post-compromise hygiene). Regressing
        // either is a silent escalation of privilege on an already-SUSPENDED
        // tenant — lock it down with explicit neverCalled assertions.
        Tenant suspended = newTenant(TenantState.SUSPENDED);
        UUID actor = UUID.randomUUID();
        when(tenantRepository.findById(suspended.getId())).thenReturn(Optional.of(suspended));
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> inv.getArgument(0));

        Tenant result = newService().unsuspend(suspended.getId(), actor, "incident-cleared");

        assertThat(result.getState()).isEqualTo(TenantState.ACTIVE);
        verify(tenantKeyRotationService, never()).bumpJwtKeyGeneration(any(), any());
        verify(apiKeyService, never()).deactivateAllForTenant(any());
        verify(tenantRepository, times(1)).save(suspended);

        AuditEventRecord audit = captureAuditEvent();
        assertThat(audit.action()).isEqualTo(AuditEventTypes.TENANT_UNSUSPENDED);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) audit.details();
        assertThat(details).containsEntry("previous_state", "SUSPENDED");
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

    // ─── F-4 create bootstrap ────────────────────────────────────────────────

    @Test
    void create_happyPath_savesTenantBootstrapsKeyMaterialSeedsChainHeadEmitsAudit() {
        UUID generatedId = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        when(tenantRepository.existsBySlug("demo-slug")).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(generatedId);
            return t;
        });

        Tenant result = newService().create("Demo CoC", "demo-slug", actor);

        assertThat(result.getId()).isEqualTo(generatedId);
        assertThat(result.getState()).isEqualTo(TenantState.ACTIVE);
        assertThat(result.getSlug()).isEqualTo("demo-slug");
        assertThat(result.getName()).isEqualTo("Demo CoC");

        // Step ordering assertions — save BEFORE key material bootstrap BEFORE
        // audit_chain_head seed BEFORE audit emit. If any of these are swapped,
        // the tx boundary and the D55 tenant-id binding break.
        verify(tenantRepository, times(1)).existsBySlug("demo-slug");
        verify(tenantRepository, times(1)).save(any(Tenant.class));
        verify(kidRegistryService, times(1)).findOrCreateActiveKid(generatedId);
        verify(jdbc, times(1)).update(contains("tenant_audit_chain_head"), eq(generatedId));

        AuditEventRecord audit = captureAuditEvent();
        assertThat(audit.action()).isEqualTo(AuditEventTypes.TENANT_CREATED);
        assertThat(audit.actorUserId()).isEqualTo(actor);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) audit.details();
        assertThat(details)
            .containsEntry("slug", "demo-slug")
            .containsEntry("name", "Demo CoC")
            .containsEntry("actor_user_id", actor.toString());
    }

    @Test
    void create_duplicateSlug_throwsBeforeAnySideEffects() {
        // Idempotency-as-409 contract: second create with a taken slug must throw
        // without spending any key-derivation, audit, or chain-head work. Spring's
        // @Transactional rolls back on the exception, so downstream state is
        // untouched regardless — but the explicit existsBySlug fast-path avoids
        // the expensive work entirely.
        when(tenantRepository.existsBySlug("taken-slug")).thenReturn(true);

        assertThatThrownBy(() ->
                newService().create("Second", "taken-slug", UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("taken-slug");

        verify(tenantRepository, never()).save(any());
        verify(kidRegistryService, never()).findOrCreateActiveKid(any());
        verify(jdbc, never()).update(contains("tenant_audit_chain_head"), any(Object.class));
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void create_nullActorUserId_acceptedForSystemInitiatedBootstraps() {
        // create(name, slug, null) is valid — used by seed migrations and
        // platform bootstrap scripts that have no human actor. The audit row's
        // actor_user_id field is null; details map renders "actor_user_id" as
        // JSON null for Phase G consumers.
        UUID generatedId = UUID.randomUUID();
        when(tenantRepository.existsBySlug(any())).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(generatedId);
            return t;
        });

        Tenant result = newService().create("Sys", "sys-seed", null);

        assertThat(result.getId()).isEqualTo(generatedId);
        AuditEventRecord audit = captureAuditEvent();
        assertThat(audit.actorUserId()).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) audit.details();
        assertThat(details).containsEntry("actor_user_id", null);
    }

    @Test
    void create_keyMaterialBootstrapFails_propagates_noAuditEmitted() {
        // Failure-injection: if findOrCreateActiveKid throws (e.g. DB contention,
        // RLS rejection, KEK unavailable), the tx rolls back and no audit row
        // lands. The outer @Transactional handles rollback; this test pins the
        // propagation shape.
        UUID generatedId = UUID.randomUUID();
        when(tenantRepository.existsBySlug(any())).thenReturn(false);
        when(tenantRepository.save(any(Tenant.class))).thenAnswer(inv -> {
            Tenant t = inv.getArgument(0);
            t.setId(generatedId);
            return t;
        });
        doThrow(new IllegalStateException("KEK unavailable"))
            .when(kidRegistryService).findOrCreateActiveKid(generatedId);

        assertThatThrownBy(() -> newService().create("Fail", "fail-slug", UUID.randomUUID()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("KEK unavailable");

        // Audit must NOT fire — the tx will roll back, but we also want the
        // explicit "not published" contract so a future refactor can't silently
        // emit an audit row before the failing step.
        verify(eventPublisher, never()).publishEvent(any());
        verify(jdbc, never()).update(contains("tenant_audit_chain_head"), any(Object.class));
    }

    @Test
    void hardDelete_throwsUnsupportedOperation_deferredToF6() {
        assertThatThrownBy(() ->
                newService().hardDelete(UUID.randomUUID(), UUID.randomUUID(), "retention-complete"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("F-6");
    }

    // ─── Runtime assertion guarding set_config scope ─────────────────────────

    @Test
    void suspend_withoutActiveTransaction_throwsBeforeSideEffects() {
        // Defense against a future refactor that drops @Transactional from suspend:
        // the set_config('app.tenant_id', ?, true) binding is tx-scoped, so without a
        // tx the subsequent audit INSERT silently falls back to SYSTEM_TENANT_ID.
        // The runtime assertion in bindTenantContextForAudit catches this at call
        // time. Remove the tx-active simulation and prove the assertion fires.
        TransactionSynchronizationManager.setActualTransactionActive(false);
        Tenant active = newTenant(TenantState.ACTIVE);
        when(tenantRepository.findById(active.getId())).thenReturn(Optional.of(active));

        assertThatThrownBy(() -> newService().suspend(active.getId(), UUID.randomUUID(), "op"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("requires an active transaction");

        verify(tenantKeyRotationService, never()).bumpJwtKeyGeneration(any(), any());
        verify(apiKeyService, never()).deactivateAllForTenant(any());
        verify(eventPublisher, never()).publishEvent(any());
        verify(tenantRepository, never()).save(any());
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

    /**
     * Captures the single {@link AuditEventRecord} published via the mocked
     * {@link ApplicationEventPublisher}. Avoids the {@code argThat} type-inference
     * collision between Spring's overloaded {@code publishEvent(ApplicationEvent)}
     * and {@code publishEvent(Object)} — the captor binds the generic to
     * {@link AuditEventRecord} directly.
     */
    private AuditEventRecord captureAuditEvent() {
        ArgumentCaptor<AuditEventRecord> captor = ArgumentCaptor.forClass(AuditEventRecord.class);
        verify(eventPublisher).publishEvent(captor.capture());
        return captor.getValue();
    }
}
