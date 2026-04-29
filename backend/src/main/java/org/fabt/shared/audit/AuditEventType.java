package org.fabt.shared.audit;

/**
 * Type-safe enumeration of every audit event action recognised by FABT.
 *
 * <p>Supersedes the prior {@code AuditEventTypes} constants class. The migration
 * was tracked in <a href="https://github.com/ccradle/finding-a-bed-tonight/issues/98">issue #98</a>
 * and landed as Slice G-0 preflight for Phase G (multi-tenant-production-readiness
 * §8). The underlying concern — typo safety for an append-only, externally-queried
 * column — is unchanged; the upgrade from {@code public static final String}
 * constants to a true Java enum gives compile-time discoverability and makes
 * it impossible to write {@code publishEvent(new AuditEventRecord(..., "ROLE_CHANEGD", ...))}.</p>
 *
 * <h2>DB representation</h2>
 *
 * <p>The database column {@code audit_events.action} remains {@code VARCHAR}.
 * Serialisation at the persistence boundary uses {@link Enum#name()}, so
 * historical rows continue to query correctly and the Phase G chain hasher
 * (§8.4) sees a stable canonical form. The enum deliberately avoids a database
 * {@code CHECK} constraint — new audit types are added organically as the
 * platform grows, and a {@code CHECK} would force a Flyway migration per new
 * type. Typo prevention at the Java layer is sufficient.</p>
 *
 * <h3>Why not a native PostgreSQL enum type?</h3>
 *
 * <p>Slice F-1 (v0.51.0 prework for {@code tenant_state}) established — with
 * warroom-assessed research and primary-source citations — that Spring Data
 * JDBC 4.x simple-type handling bypasses user-registered converters for enums
 * (spring-data-relational #1689, #1697, #1705, #1935, #2083). Independent of
 * the framework: PG enums have no supported {@code DROP VALUE} and
 * adding/removing values forces an {@code ACCESS EXCLUSIVE} table rewrite.
 * {@code VARCHAR + enum.name()} is the established FABT pattern ({@code
 * reservation.status}, {@code shelter.deactivation_reason}, {@code
 * tenant.state}).</p>
 *
 * <h2>When adding a new case</h2>
 *
 * <ol>
 *   <li>Add the enum case here with a Javadoc block describing the emitter,
 *       the details JSON payload, and the actor/target convention.</li>
 *   <li>Add a corresponding {@code @Test} in {@code AuditEventTypeTest} pinning
 *       {@code .name()} to the wire value.</li>
 *   <li>If the event is security-evidence (must survive caller rollback),
 *       persist via {@link DetachedAuditPersister#persistDetached} rather than
 *       the default event-bus path.</li>
 * </ol>
 *
 * <p><b>Casey Drummond's chain-of-custody directive:</b> once a value ships to
 * production, the {@code .name()} is contract-stable forever. Historical
 * {@code audit_events} rows and external compliance queries depend on it.
 * Renames require a data migration.</p>
 */
public enum AuditEventType {

    // ─── bed-hold-integrity (v0.34.0, Issue #102 RCA) ───

    /**
     * Written by the bed-holds reconciliation tasklet whenever a corrective
     * snapshot is fired to bring {@code bed_availability.beds_on_hold} back into
     * agreement with the actual count of HELD reservations for that
     * shelter+population. Also written by the V45 one-time backfill migration
     * with {@code correction_source='V45_backfill'} in the details payload.
     *
     * <p>Payload: {@code {shelter_id, population_type, snapshot_value_before,
     * actual_count, delta}} for reconciliation rows. V45 backfill rows include
     * additional {@code correction_source} and {@code github_issue} fields.</p>
     *
     * <p>Actor: {@code null} (system-driven).</p>
     */
    BED_HOLDS_RECONCILED,

    // ─── coc-admin-escalation: CoC admin actions on DV referrals ───

    /**
     * An outreach worker (or other DV-authorized role) requested a new DV referral
     * via {@code POST /api/v1/dv-referrals}. Detail blob: {@code shelter_id},
     * {@code shelter_name}, {@code urgency} — zero client PII.
     */
    DV_REFERRAL_REQUESTED,

    /**
     * A CoC admin claimed a pending DV referral via {@code POST /api/v1/dv-referrals/{id}/claim}.
     * Detail blob: {@code {referral_id, claimed_until}}. Zero PII.
     */
    DV_REFERRAL_CLAIMED,

    /**
     * A CoC admin released a claim manually, OR the auto-release scheduler
     * cleared an expired claim ({@code actor_user_id = system}).
     * Detail blob: {@code {referral_id, reason: "manual"|"timeout"}}. Zero PII.
     */
    DV_REFERRAL_RELEASED,

    /**
     * A CoC admin reassigned a pending DV referral via
     * {@code POST /api/v1/dv-referrals/{id}/reassign}. Detail blob:
     * {@code {referral_id, target_type: COORDINATOR_GROUP|COC_ADMIN_GROUP|SPECIFIC_USER,
     * target_id, previous_assignee_id}}. SPECIFIC_USER target type breaks the
     * escalation chain by design (PagerDuty pattern).
     */
    DV_REFERRAL_REASSIGNED,

    /**
     * A coordinator accepted a pending DV referral via the normal screening
     * path. Detail blob: {@code {shelter_id}}. Distinct from
     * {@link #DV_REFERRAL_ADMIN_ACCEPTED} so the chain-of-custody trail can
     * differentiate "coordinator screened normally" from "admin intervened."
     */
    DV_REFERRAL_ACCEPTED,

    /**
     * A coordinator rejected a pending DV referral via the normal screening
     * path. Detail blob: {@code {shelter_id, reason}}. Distinct from
     * {@link #DV_REFERRAL_ADMIN_REJECTED}.
     */
    DV_REFERRAL_REJECTED,

    /**
     * A CoC admin (not a coordinator) accepted a pending DV referral directly
     * via {@code PATCH /api/v1/dv-referrals/{id}/accept}. Distinct from
     * {@link #DV_REFERRAL_ACCEPTED} so the chain-of-custody trail can
     * differentiate admin overrides from normal coordinator screening.
     * Detail blob: {@code {referral_id, shelter_id}}.
     */
    DV_REFERRAL_ADMIN_ACCEPTED,

    /**
     * A CoC admin rejected a pending DV referral directly via
     * {@code PATCH /api/v1/dv-referrals/{id}/reject}. Distinct from coordinator
     * rejection. Detail blob: {@code {referral_id, shelter_id, reason}}. The
     * admin-supplied reason field is the admin's responsibility to keep
     * PII-free; the UI shows a warning above the field.
     */
    DV_REFERRAL_ADMIN_REJECTED,

    /**
     * The {@code dvReferralDemoCleanup} batch job deleted stale PENDING DV
     * referrals from a demo tenant (G-4.5 §6.10). The job runs every 6 hours
     * on demo deployments and removes PENDING referrals older than 48 hours
     * from tenants whose slug starts with {@code dev-}. Emitted under each
     * affected tenant's audit chain (one row per tenant per run that deleted
     * rows). Detail blob: {@code {deleted_count, cutoff_at, tenant_slug}}.
     * Zero PII — the deleted referrals themselves carry no client PII per
     * the DV opaque-token design.
     */
    DV_REFERRAL_DEMO_CLEANUP,

    /**
     * A CoC admin updated the per-tenant escalation policy via
     * {@code PATCH /api/v1/admin/escalation-policy/{eventType}}. The policy is
     * append-only versioned, so this audit row records the new version
     * created, not a mutation of an existing row. Detail blob:
     * {@code {policy_id, event_type, version, previous_version}}.
     */
    ESCALATION_POLICY_UPDATED,

    // ─── shelter-activate-deactivate (Issue #108) ───

    /**
     * A CoC admin deactivated a shelter via {@code PATCH /api/v1/shelters/{id}/deactivate}.
     * Detail blob: {@code {shelter_id, shelter_name, deactivation_reason}}.
     */
    SHELTER_DEACTIVATED,

    /**
     * A CoC admin reactivated a shelter via {@code PATCH /api/v1/shelters/{id}/reactivate}.
     * Detail blob: {@code {shelter_id, shelter_name}}.
     */
    SHELTER_REACTIVATED,

    /**
     * An admin toggled the {@code dvShelter} flag on a shelter record via
     * {@code PUT /api/v1/shelters/{id}}. Detail blob: {@code {shelter_id,
     * shelter_name, previous_value, new_value}}. Load-bearing for DV
     * confidentiality posture (Keisha Thompson warroom): flipping this flag
     * changes RLS visibility of shelter records.
     */
    SHELTER_DV_FLAG_CHANGED,

    /**
     * An admin edited the physical address of a DV-flagged shelter. Distinct
     * from regular address edits because DV shelter addresses are confidential
     * (VAWA-comparable posture). Detail blob: {@code {shelter_id, shelter_name,
     * changed_fields}}. The old and new address values are NOT written to the
     * audit blob — only the field names that changed — so audit dumps do not
     * re-expose the confidential address.
     */
    DV_SHELTER_ADDRESS_CHANGED,

    // ─── auth + user management ───

    /**
     * A platform admin changed the roles of a user via
     * {@code PATCH /api/v1/users/{id}/roles}. Detail blob:
     * {@code {oldRoles, newRoles}}. Immediately invalidates any in-flight
     * JWT because role claims are baked into the token at sign time.
     */
    ROLE_CHANGED,

    /**
     * A platform admin changed a user's DV access permission. Detail blob:
     * {@code {oldDvAccess, newDvAccess}}. Invalidates in-flight JWTs because
     * DV access is a token claim.
     */
    DV_ACCESS_CHANGED,

    /**
     * A platform admin deactivated a user via
     * {@code PATCH /api/v1/users/{id}/deactivate}. Detail blob: {@code null}
     * (the userId is in {@code targetUserId}). Deactivation disables login
     * and revokes any outstanding session.
     */
    USER_DEACTIVATED,

    /**
     * A platform admin reactivated a previously-deactivated user.
     * Detail blob: {@code null}.
     */
    USER_REACTIVATED,

    /**
     * An admin generated a one-time access code for a user (password-reset
     * and similar flows). Detail blob: {@code null}. Companion audit to
     * {@link #ACCESS_CODE_USED} closes the chain of custody.
     */
    ACCESS_CODE_GENERATED,

    /**
     * A user consumed a one-time access code to authenticate. Detail blob:
     * {@code null}. Fires from {@code AuthController} during the access-code
     * login flow.
     */
    ACCESS_CODE_USED,

    // ─── TOTP / MFA ───

    /**
     * A user enabled TOTP MFA for their own account. Detail blob: {@code null}.
     * Actor and target are both the user's own id (self-service action).
     */
    TOTP_ENABLED,

    /**
     * A user regenerated their own TOTP backup codes. Detail blob: {@code null}.
     * Actor and target are both the user's own id.
     */
    BACKUP_CODES_REGENERATED,

    // ─── HMIS export (tenant-scoped) ───

    /**
     * A COC_ADMIN triggered an HMIS export of their tenant's bed inventory
     * via {@code POST /api/v1/hmis/push}. Tenant-scoped action — distinct
     * from {@link #PLATFORM_HMIS_EXPORTED} (the platform-operator-driven
     * cross-tenant flavour, deferred to F14). Detail blob:
     * {@code {"vendorTypes": [...], "outboxEntriesCreated": N}}.
     *
     * <p>Phase G-4.4 §F16 mitigation: the HMIS push endpoint reverted from
     * {@code @PlatformAdminOnly} to {@code COC_ADMIN}-only because its
     * service contract reads {@code TenantContext}. The revert broadened
     * authority (CoC admins who never had PLATFORM_ADMIN are now
     * authorized) and dropped the platform_admin_access_log audit trail
     * G-4.3 had attached. This audit_event row is the per-tenant
     * replacement: every CoC admin's HMIS export attempt produces a row
     * in {@code audit_events} with actor identity + vendor list, scoped
     * to the calling tenant.
     */
    HMIS_EXPORT_TRIGGERED,

    /**
     * A platform admin forcibly disabled TOTP on another user's account via
     * {@code POST /api/v1/users/{id}/totp/disable}. Detail blob: {@code null}.
     * Distinct from self-disable (which is {@code TOTP_DISABLED}, not yet
     * in use). The admin-initiated flavour leaves a stronger trail because
     * it short-circuits the user's second factor.
     */
    TOTP_DISABLED_BY_ADMIN,

    /**
     * A platform admin regenerated backup codes on another user's account.
     * Detail blob: {@code null}. Distinct from self-regenerate
     * ({@link #BACKUP_CODES_REGENERATED}) for the same reason as
     * {@link #TOTP_DISABLED_BY_ADMIN}.
     */
    BACKUP_CODES_REGENERATED_BY_ADMIN,

    // ─── Phase A — JWT key rotation (multi-tenant-production-readiness §A) ───

    /**
     * Emitted by {@code TenantKeyRotationService.bumpJwtKeyGeneration} after
     * an atomic 5-action rotation: (1) inactivate prior gen, (2) insert next
     * gen, (3) revoke outstanding kids, (4) bump counter, (5) audit. Detail
     * blob: {@code {previousGeneration, newGeneration, revokedKidCount}}.
     * Also called from {@code TenantLifecycleService.suspend} as part of the
     * suspend quarantine (Phase F).
     */
    JWT_KEY_GENERATION_BUMPED,

    /**
     * Emitted by {@code SecretEncryptionService.decryptForTenant} when the
     * legacy v0 envelope fallback path fires. Expected only for a narrow
     * window of V74-migrated rows; after Phase A.5 ripens, non-zero rate is
     * an attack indicator. Detail blob:
     * {@code {tenantId, purpose, note}}. Throttled per (tenant, purpose) to
     * bound audit volume.
     */
    CIPHERTEXT_V0_DECRYPT,

    /**
     * Emitted by {@code GlobalExceptionHandler} when {@code SecretEncryptionService}
     * rejects a decrypt attempt because the envelope's {@code kid} resolves to a
     * different tenant than the caller's context (Phase F-6 cross-tenant
     * ciphertext rejection). Detail blob:
     * {@code {kid, expectedTenantId, actualTenantId, actorUserId, sourceIp}}.
     * Security-evidence — must survive caller rollback (persisted via the
     * standard listener path; the handler runs at the exception boundary where
     * no caller tx remains).
     */
    CROSS_TENANT_CIPHERTEXT_REJECTED,

    /**
     * Emitted by {@code GlobalExceptionHandler} when {@code JwtService.validate}
     * detects that a JWT's {@code kid} resolves to a different tenant than the
     * body claim's {@code tenantId} — the kid-confusion attack (Phase A4 D25).
     * Detail blob: {@code {kid, expectedTenantId, actualTenantId, actorUserId,
     * sourceIp, claimsTenantId, claimsSub, claimsIat, claimsExp}}. W1 fold-in
     * adds body-claim fields for incident-response forensics.
     */
    CROSS_TENANT_JWT_REJECTED,

    // ─── Phase C — cache isolation (multi-tenant-production-readiness §C) ───

    /**
     * Emitted by {@code TenantScopedCacheService.invalidateTenant(UUID)} when
     * a tenant's cache entries are evicted across every registered cache name.
     * Fires at tenant suspend / hard-delete (Phase F F4) and on demand by the
     * platform-admin API.
     *
     * <p>Detail blob: {@code {tenantId, perCacheEvictionCounts: {cacheName: N, ...}}}.
     * Actor: {@code null} for FSM-driven calls; platform-admin UUID for manual
     * invalidations. Target: {@code null} (operation scope is the tenant itself).
     *
     * <p>Persisted via the event-bus path ({@code ApplicationEventPublisher} →
     * {@code AuditEventService.onAuditEvent} → {@code AuditEventPersister} with
     * {@code PROPAGATION_REQUIRED}). Normal rollback semantics apply.
     */
    TENANT_CACHE_INVALIDATED,

    /**
     * Emitted by {@code TenantScopedCacheService.get} when the on-read tenant
     * verification (cached-value stamp mismatch) detects that the envelope's
     * stamped tenant does not match the reader's {@code TenantContext}.
     *
     * <p>Detail blob: {@code {cacheName, expectedTenant, observedTenant}}.
     * Actor: current user from {@code TenantContext.getUserId()}. Target: {@code null}.
     *
     * <p><b>Persisted via {@link DetachedAuditPersister}</b> with
     * {@code PROPAGATION_REQUIRES_NEW}. Security-evidence: must survive caller
     * rollback so an attacker triggering an ISE cannot erase the audit trail.
     */
    CROSS_TENANT_CACHE_READ,

    /**
     * Emitted by {@code TenantScopedCacheService.get} when it encounters a
     * cache entry whose underlying value is not a {@code TenantScopedValue}
     * envelope. Indicates a caller wrote via raw {@code CacheService}
     * bypassing the wrapper. Detail blob: {@code {cacheName, observedType}}.
     * Value fragments are deliberately omitted (avoid leaking cached payload).
     */
    MALFORMED_CACHE_ENTRY,

    /**
     * Emitted by {@code EscalationPolicyService.findByTenantAndId} when the
     * on-read tenant verification detects that the stored policy's
     * {@code tenantId} does not match the caller's {@code TenantContext}
     * AND the policy is not a platform-default (null-tenant) row.
     *
     * <p>Detail blob: {@code {policyId, expectedTenant, observedTenant}}.
     * Persisted via {@link DetachedAuditPersister} — security-evidence.
     */
    CROSS_TENANT_POLICY_READ,

    // ─── Phase F — tenant lifecycle (multi-tenant-production-readiness §F) ───
    //
    // Every lifecycle audit row carries a details JSON with at minimum:
    //   { actor_user_id, justification, previous_state, new_state }
    // Phase G's platform_admin_access_log will read actor_user_id + justification
    // directly; the schema is designed so §G3 has no retrofit work.

    /**
     * Emitted by {@code TenantLifecycleService.create} after a successful atomic
     * bootstrap (tenant row + JWT gen-1 key + DEKs + empty audit_chain_head seed).
     * Detail blob: {@code {actor_user_id, slug, name, data_residency_region}}.
     */
    TENANT_CREATED,

    /**
     * Emitted by {@code TenantLifecycleService.suspend} after the 5-action
     * quarantine (bump JWT gen, deactivate API keys, stop worker dispatch,
     * flip state, audit). Detail blob:
     * {@code {actor_user_id, justification, previous_state, revokedKidCount}}.
     */
    TENANT_SUSPENDED,

    /**
     * Emitted by {@code TenantLifecycleService.unsuspend} when a SUSPENDED
     * tenant is restored to ACTIVE after incident resolution.
     * Detail blob: {@code {actor_user_id, justification, suspend_audit_row_id}}.
     */
    TENANT_UNSUSPENDED,

    /**
     * Emitted by {@code TenantLifecycleService.offboard} when state transitions
     * to OFFBOARDING. The export workflow runs as part of the same transition (F-5).
     * Detail blob: {@code {actor_user_id, justification, previous_state,
     * export_receipt_uri}}.
     */
    TENANT_OFFBOARDING_STARTED,

    /**
     * Emitted by {@code TenantLifecycleService.archive} after the export-complete
     * check. Starts the 30-day retention window before hard-delete is permitted.
     * Detail blob: {@code {actor_user_id, justification, archived_at,
     * export_receipt_uri}}.
     */
    TENANT_ARCHIVED,

    /**
     * Emitted by COC_ADMIN-grade endpoints that mutate {@code tenant.config}
     * JSONB without changing the tenant lifecycle state. First emitter:
     * {@code ReservationConfigController.updateHoldDuration} (transitional-
     * reentry-support task 4.5, slice 2C / 2D warroom B1 fix). Future emitters
     * for other in-tenant config keys should reuse this type rather than
     * minting per-key audit types — the {@code config_key} field in the detail
     * blob discriminates.
     *
     * <p>Detail blob: {@code {config_key, old_value, new_value}}. Actor is the
     * COC_ADMIN user; target_id is the tenant id. Phase G's
     * {@code JustificationValidationFilter} does NOT apply here (these are
     * in-tenant operational config writes, not platform-grade actions).
     */
    TENANT_CONFIG_UPDATED,

    // ─── Phase F — lifecycle attempt rejections (slice F-3.6) ───
    //
    // Emitted when a lifecycle transition is rejected by the §D8 FSM. Persisted
    // via DetachedAuditPersister (REQUIRES_NEW) so the audit row survives the
    // IllegalStateTransitionException that rolls back the caller's transaction.
    // Marcus warroom F-2 HIGH + F-3.6 plan. Without these, an attacker could
    // probe tenant ids via the admin endpoints with no forensic trail.

    /** {@code TenantLifecycleService.suspend} rejected (non-ACTIVE state). */
    TENANT_SUSPEND_REJECTED,

    /** {@code TenantLifecycleService.unsuspend} rejected (not SUSPENDED). */
    TENANT_UNSUSPEND_REJECTED,

    /** {@code TenantLifecycleService.offboard} rejected (ARCHIVED/DELETED cannot offboard). */
    TENANT_OFFBOARD_REJECTED,

    /** {@code TenantLifecycleService.archive} rejected (not OFFBOARDING). */
    TENANT_ARCHIVE_REJECTED,

    /** {@code TenantLifecycleService.hardDelete} rejected (not ARCHIVED or retention not met). */
    TENANT_HARD_DELETE_REJECTED,

    /**
     * Emitted by {@code TenantLifecycleService.hardDelete} as the crypto-shred trigger.
     *
     * <p><b>Persisted BEFORE the destructive transaction</b> (separate tx + commit
     * first, then the tenant + tenant_key_material + tenant_audit_chain_head
     * DELETE cascade). Rationale: a failed hardDelete attempt leaves no row for
     * a post-mortem to inspect if the audit INSERT is inside the destructive tx
     * and both roll back together. Attempted-shred evidence survives.
     *
     * <p>Detail blob: {@code {actor_user_id, justification, archived_at,
     * retention_days}}.</p>
     */
    TENANT_HARD_DELETED,

    // ─── Phase G-4.3 — platform-admin actions (issue #141) ───────────────
    //
    // Emitted by the @PlatformAdminOnly AOP aspect alongside a
    // platform_admin_access_log (PAL) row inside one REQUIRES_NEW
    // transaction (Decision 11). Distinct from the existing TENANT_*
    // values because each captures a different facet of the same action:
    // TENANT_CREATED / TENANT_SUSPENDED / etc. are emitted by the
    // service-layer state-machine when the action effects; PLATFORM_*
    // here are emitted by the aspect at the controller boundary with
    // platform-admin context (justification, requestor, request fingerprint).
    // A single platform-driven tenant suspend produces TWO audit_events
    // rows — TENANT_SUSPENDED (effect) and PLATFORM_TENANT_SUSPENDED
    // (intent + authority). Both are forensic value; they answer different
    // queries.

    /**
     * A PLATFORM_OPERATOR triggered tenant creation via
     * {@code POST /api/v1/tenants}. Detail blob includes
     * {@code platform_admin_access_log_id, platform_user_id,
     * justification_excerpt, request_method, request_path}. Aspect-emitted.
     * AE.tenant_id = the new tenant's id (chain seeded in the new tenant's
     * brand-new chain head).
     */
    PLATFORM_TENANT_CREATED,

    /**
     * A PLATFORM_OPERATOR updated tenant display-name metadata via
     * {@code PUT /api/v1/tenants/{id}}. Aspect-emitted; AE.tenant_id =
     * target tenant. Distinct from {@link #PLATFORM_TENANT_CREATED} so
     * oncall + SIEM rules can distinguish create-vs-update activity in
     * audit timelines.
     */
    PLATFORM_TENANT_UPDATED,

    /**
     * A PLATFORM_OPERATOR updated a tenant's observability config via
     * {@code PUT /api/v1/tenants/{id}/observability}. Aspect-emitted;
     * AE.tenant_id = target tenant. Distinct event type so changes to
     * monitoring/tracing posture can be queried independently of other
     * tenant mutations.
     */
    PLATFORM_TENANT_OBSERVABILITY_UPDATED,

    /**
     * A PLATFORM_OPERATOR changed a tenant's DV-address visibility policy
     * via {@code PUT /api/v1/tenants/{id}/dv-address-policy}. Aspect-
     * emitted; AE.tenant_id = target tenant. Distinct event type because
     * DV-address policy is the single highest-sensitivity tenant config —
     * compliance reviews query this action class specifically.
     */
    PLATFORM_DV_ADDRESS_POLICY_CHANGED,

    /**
     * A PLATFORM_OPERATOR triggered tenant suspension via
     * {@code POST /api/v1/tenants/{id}/suspend}. AE.tenant_id = target tenant.
     * Chained in target tenant's chain. See {@link #PLATFORM_TENANT_CREATED}
     * for details payload shape.
     */
    PLATFORM_TENANT_SUSPENDED,

    /**
     * A PLATFORM_OPERATOR restored a SUSPENDED tenant via
     * {@code POST /api/v1/tenants/{id}/unsuspend}. AE.tenant_id = target tenant.
     */
    PLATFORM_TENANT_UNSUSPENDED,

    /**
     * A PLATFORM_OPERATOR began the offboarding workflow via
     * {@code POST /api/v1/tenants/{id}/offboard}. AE.tenant_id = target tenant.
     */
    PLATFORM_TENANT_OFFBOARDED,

    /**
     * A PLATFORM_OPERATOR triggered hard-delete (crypto-shred) via
     * {@code DELETE /api/v1/tenants/{id}}. AE.tenant_id is forced to
     * SYSTEM_TENANT_ID (Decision 13) so the audit row survives the cascade
     * delete of the target tenant's chain head. Without that override, the
     * row would either fail the FK on insert or be deleted with the cascade.
     */
    PLATFORM_TENANT_HARD_DELETED,

    /**
     * A PLATFORM_OPERATOR triggered per-tenant JWT key rotation via
     * {@code POST /api/v1/tenants/{id}/keys/rotate}. AE.tenant_id = target tenant.
     */
    PLATFORM_KEY_ROTATED,

    /**
     * A PLATFORM_OPERATOR triggered an HMIS export via
     * {@code POST /api/v1/hmis/export}. AE.tenant_id = source tenant.
     */
    PLATFORM_HMIS_EXPORTED,

    /**
     * A PLATFORM_OPERATOR exercised an OAuth2 test-connection endpoint via
     * {@code POST /api/v1/tenants/{id}/oauth2-providers/{p}/test}.
     * AE.tenant_id = target tenant.
     */
    PLATFORM_OAUTH2_TESTED,

    /**
     * A PLATFORM_OPERATOR triggered a batch job via
     * {@code POST /api/v1/admin/batch-jobs/{name}/run}. Platform-wide action;
     * AE.tenant_id = SYSTEM_TENANT_ID, NOT chained.
     */
    PLATFORM_BATCH_JOB_TRIGGERED,

    /**
     * A PLATFORM_OPERATOR invoked the dev-only test-reset endpoint. Platform-
     * wide action; AE.tenant_id = SYSTEM_TENANT_ID, NOT chained. Endpoint is
     * disabled in production via profile gating.
     */
    PLATFORM_TEST_RESET_INVOKED,

    /**
     * A {@code platform_user} was auto-locked after 5 failed MFA attempts in
     * 15 minutes (V88 lockout policy). Emitted directly from
     * {@code PlatformAuthService.recordFailureAndMaybeLock} via
     * {@code PlatformAdminAccessLogger.logLockout(userId)}, NOT via the
     * {@code @PlatformAdminOnly} aspect — the lockout fires from an internal
     * service path the aspect can't reach (D6).
     */
    PLATFORM_USER_LOCKED_OUT,

    /**
     * A PLATFORM_OPERATOR created a new {@code platform_user} via
     * {@code POST /api/v1/platform/users} (Phase H+). Platform-wide action;
     * AE.tenant_id = SYSTEM_TENANT_ID, NOT chained.
     */
    PLATFORM_USER_CREATED,

    /**
     * A PLATFORM_OPERATOR reset a locked-out platform_user back to bootstrap
     * state via the Phase H+ recovery endpoint. Folded in from F5 follow-up
     * captured during G-4.2 hardening commit aceb1d9. Platform-wide action;
     * AE.tenant_id = SYSTEM_TENANT_ID, NOT chained.
     */
    PLATFORM_USER_RESET_TO_BOOTSTRAP,

    // ─── Test-infrastructure sentinel (do not use in production code) ───

    /**
     * Reserved for audit-infrastructure tests that need to publish a synthetic
     * {@link AuditEventRecord} to verify plumbing (e.g. Phase B D55 orphan-path
     * SYSTEM_TENANT_ID fallback, B11 allowlist inner-tenant re-bind). Keeping
     * this a distinct case — rather than overloading a business case like
     * {@code BED_HOLDS_RECONCILED} — prevents test writes from polluting
     * (1) the audit trail's business meaning, (2) {@code fabt.audit.system_insert.count}
     * and {@code fabt.audit.rls_rejected.count} Prometheus counters, which
     * operators alert on, and (3) future compliance queries that filter by
     * {@code action}.
     *
     * <p><b>Operators:</b> you can safely filter {@code action != 'TEST_PROBE'}
     * in any audit dashboard or alert rule. No production code path references
     * this case (should be grep-clean outside {@code src/test}).</p>
     *
     * <p><b>Developers:</b> do not add a production reference. Tests that need
     * unique row identification should pair this case with a UUID probe token
     * in the {@code details} payload.</p>
     */
    TEST_PROBE
}
