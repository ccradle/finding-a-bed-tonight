# Phase B — audited regulated-table write sites

**Purpose.** Every production-code write to a Phase B regulated table
must bind `app.tenant_id` before the statement runs, or the FORCE RLS
policies from V68/V69 reject it. This file is the single-source-of-
truth list, per warroom W-SYS-1 from the Phase B review
(2026-04-17). CODEOWNERS gate: any PR adding / removing entries here
requires Marcus Webb + Casey Drummond sign-off.

**Current status.** 17 write sites total, **0 gaps open**. Both GAPs surfaced in the initial audit (`AccessCodeService.validateCode` UPDATE and `HmisPushService.createOutboxEntriesForTenant` INSERT) were patched in the same session; see the "Gaps found — resolved" section below. Status: **green — all regulated-table writes bound via Mechanism A / B / C. Ready for W-SYS-2 ArchUnit mechanical rule in a follow-up PR.**

**Regenerate.** Repeat the grep pattern in the W-SYS-1 warroom prompt
against main; diff against this file.

## Legend

- **A** — Caller pre-binds via `TenantContext.runWithContext(tenantId, …)`. `RlsDataSourceConfig.applyRlsContext` sets `app.tenant_id` at the next connection borrow.
- **B** — Method itself calls `SELECT set_config('app.tenant_id', ?, is_local=true)` under `@Transactional`.
- **C** — `AuditEventService` three-level fallback (TenantContext → session GUC → SYSTEM_TENANT_ID). Ultimately delegates to `AuditEventPersister.persist` which is Mechanism B.
- **GAP** — No binding in place; write will be rejected by FORCE RLS (or silently match zero rows for a permissive-USING DELETE/UPDATE).

## Table: `audit_events`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `AuditEventPersister.persist` | `backend/src/main/java/org/fabt/shared/audit/AuditEventPersister.java:80-92` | **B** | `@Transactional`; line 82 runs `SELECT set_config('app.tenant_id', ?, true)` before `repository.save(entity)` at line 91. Cross-bean proxy call from `AuditEventService.onAuditEvent` guarantees `@Transactional` engages (self-invocation bug-A/D fix). |
| `AuditEventService.onAuditEvent` (14 publisher sites via `ApplicationEventPublisher`) | `backend/src/main/java/org/fabt/shared/audit/AuditEventService.java:102-171` | **C** | Three-level lookup at lines 119-141: (1) `TenantContext.getTenantId()`, (2) `tryReadSessionTenantId()` reading `current_setting('app.tenant_id', true)`, (3) `SYSTEM_TENANT_ID` sentinel + counter + WARN log. Delegates to `persister.persist` (Mechanism B). Publishers: `UserService`, `AccessCodeService`, `TotpController` (×4), `TenantKeyRotationService`, `ShelterService` (×4), `EscalationPolicyController`, `ReferralTokenService`, `ReferralTokenController`. |
| `BedHoldsReconciliationJobConfig.writeAuditRowDirect` (via `auditEventRepository.save`) | `backend/src/main/java/org/fabt/availability/batch/BedHoldsReconciliationJobConfig.java:198-232` (save at :225) | **A** | Invoked inside `TenantContext.runWithContext(row.tenantId(), true, …)` lambda at `:150-169`. Binding applies when the virtual thread borrows a connection (tasklet uses `ResourcelessTransactionManager` so no outer tx holds a connection). Method is `@TenantUnscoped` at the tasklet level — but the per-row context scope satisfies RLS regardless. |

## Table: `hmis_audit_log`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `HmisAuditRepository.insert` via `HmisPushService.processEntry` (SUCCESS branch) | `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:191-193`; SQL at `backend/src/main/java/org/fabt/hmis/repository/HmisAuditRepository.java:33-44` | **A** | `processOutbox` at :150 submits each entry to a virtual-thread executor; each child wraps `processEntry` in `TenantContext.runWithContext(entry.getTenantId(), false, …)` at :155. Child thread borrows a fresh connection — `applyRlsContext` binds `app.tenant_id`. |
| `HmisAuditRepository.insert` via `HmisPushService.processEntry` (FAILED / DEAD_LETTER branch) | `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:208-210` | **A** | Same `runWithContext` scope as SUCCESS branch. |

## Table: `hmis_outbox`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `HmisOutboxRepository.insert` via `HmisPushService.createOutboxEntriesForCurrentTenant` | `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:95-99, 136`; SQL at `backend/src/main/java/org/fabt/hmis/repository/HmisOutboxRepository.java:38-49` | **A** | Called from `HmisExportController.manualPush` (HTTP path; TenantContextFilter has bound TenantContext before the `@Transactional` boundary opens → connection borrow at tx entry applies the binding). |
| `HmisOutboxRepository.insert` via `HmisPushService.createOutboxEntriesForTenant` (batch path) | `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:115-144, 136` | **GAP (conditional)** | Called from `HmisPushJobConfig.createOutboxTasklet` at `backend/src/main/java/org/fabt/analytics/batch/HmisPushJobConfig.java:82-101`. The tasklet iterates `tenantService.findAll()` and calls `createOutboxEntriesForTenant(tenant.getId())` WITHOUT wrapping in `TenantContext.runWithContext`. The service method is `@Transactional @TenantUnscoped` and takes the tenantId as a parameter but never binds `app.tenant_id`. **Under FORCE RLS on `hmis_outbox`, the INSERT at :136 will be rejected with 42501** unless a scheduler layer (e.g. `BatchJobScheduler.runJob`) binds TenantContext around the job run. Verify: `BatchJobScheduler.registerJob("hmisPush", hmisPushJob(), "0 0 */6 * * *")` at `HmisPushJobConfig.java:112` passes no tenant — the scheduler runs under `dvAccess=false` default, which does NOT bind a tenant. **This is the same failure pattern as the `password_reset_token` pre-auth path — fix via `set_config` inside `createOutboxEntriesForTenant` (Mechanism B) or via `TenantContext.runWithContext` in the tasklet loop (Mechanism A).** |
| `HmisOutboxRepository.updateStatus` via `HmisPushService.processEntry` (×3: vendor-missing, SUCCESS, DEAD_LETTER, PENDING-retry) | `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:175, 189, 207, 212`; SQL at `backend/src/main/java/org/fabt/hmis/repository/HmisOutboxRepository.java:63-67` | **A** | Inside `runWithContext(entry.getTenantId(), …)` scope at `HmisPushService.java:155`. |
| `HmisOutboxRepository.resetToPending` via `HmisPushService.retryDeadLetter` | `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:229-232`; SQL at `backend/src/main/java/org/fabt/hmis/repository/HmisOutboxRepository.java:69-72` | **A** | Called from `HmisExportController.retryDeadLetter` at `backend/src/main/java/org/fabt/hmis/api/HmisExportController.java:181-188` (HTTP path — TenantContextFilter binds tenant before `@Transactional` boundary). |

## Table: `password_reset_token`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `PasswordResetTokenPersister.writeToken` (UPDATE invalidate + INSERT new) | `backend/src/main/java/org/fabt/auth/service/PasswordResetTokenPersister.java:57-70` | **B** | `@Transactional`; `bindTenantGuc(tenantId)` at :59 runs `SELECT set_config('app.tenant_id', ?, true)` before the UPDATE at :61 and the INSERT at :66. Called from pre-auth `PasswordResetService.requestReset` where TenantContext is unbound. |
| `PasswordResetTokenPersister.deleteToken` | `backend/src/main/java/org/fabt/auth/service/PasswordResetTokenPersister.java:77-86` | **B** | `@Transactional`; `bindTenantGuc(tenantId)` at :79 before DELETE at :80. Called from `PasswordResetService.requestReset` email-delivery-failure recovery path. |
| `PasswordResetTokenPersister.markTokenUsed` | `backend/src/main/java/org/fabt/auth/service/PasswordResetTokenPersister.java:94-100` | **B** | `@Transactional`; `bindTenantGuc(tenantId)` at :96 before UPDATE at :97. Called from pre-auth `PasswordResetService.resetPassword` with tenantId resolved from the token's user row. |
| `AccessCodeCleanupScheduler.purgeForTenant` (DELETE password_reset_token) | `backend/src/main/java/org/fabt/auth/service/AccessCodeCleanupScheduler.java:64-76` (DELETE at :70-73) | **A** | Inside `TenantContext.callWithContext(tenantId, false, …)` at :65. Scheduler iterates `tenantService.findAll()` at :45 and binds each tenant before its DELETE. |

## Table: `one_time_access_code`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `AccessCodeService.generateCode` (INSERT) | `backend/src/main/java/org/fabt/auth/service/AccessCodeService.java:55-82` (INSERT at :73-76) | **A** | Called from `AccessCodeController.generateAccessCode` (HTTP path; TenantContextFilter binds before `@Transactional`). `tenantId` is also passed as a parameter and included in the row, matching the bound `app.tenant_id`. |
| `AccessCodeService.validateCode` (UPDATE `used=true`) | `backend/src/main/java/org/fabt/auth/service/AccessCodeService.java:91-118` (UPDATE at :110) | **GAP** | Called from `AuthController.accessCodeLogin` at `backend/src/main/java/org/fabt/auth/api/AuthController.java:241-279`. This is a **pre-auth endpoint** — `TenantContextFilter` has NOT run at method entry. The method is `@Transactional @TenantUnscopedQuery` and opens a connection with `app.tenant_id=''` (empty string per `RlsDataSourceConfig.applyRlsContext` line 129). The UPDATE at :110 targets a row by `id` without re-binding `app.tenant_id`. V68 `otac_update_restrictive` checks `tenant_id = fabt_current_tenant_id()` — **when `app.tenant_id` is empty, `fabt_current_tenant_id()` returns NULL and the USING/WITH CHECK clauses evaluate NULL → zero rows updated (silent failure)**. The access-code login then returns the user (because the code hash matched) but the row is never flagged `used=true`, enabling code replay until expiry. The publishEvent at `AuthController.java:264-267` binds TenantContext AFTER validateCode returns, which is too late for the UPDATE inside validateCode. **Fix: extract the UPDATE to an `AccessCodePersister` analog to `PasswordResetTokenPersister` that binds `app.tenant_id` via `set_config` (Mechanism B) before the UPDATE, using the `user.getTenantId()` already resolved at :96.** |
| `AccessCodeCleanupScheduler.purgeForTenant` (DELETE one_time_access_code) | `backend/src/main/java/org/fabt/auth/service/AccessCodeCleanupScheduler.java:64-76` (DELETE at :66-69) | **A** | Inside `TenantContext.callWithContext(tenantId, false, …)` at :65. |

## Table: `tenant_key_material`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `TenantKeyRotationService.bumpJwtKeyGeneration` (UPDATE mark inactive + INSERT next gen) | `backend/src/main/java/org/fabt/shared/security/TenantKeyRotationService.java:120-257` (set_config at :130-131; UPDATE at :191-194; INSERT at :200-203) | **B** | `@Transactional` at :120; explicit `SELECT set_config('app.tenant_id', ?, true)` at :130 before any regulated write. Design note at :122-129 documents the D46 parameterized-set_config pattern + why B11 forbids nested `runWithContext`. |
| `KidRegistryService.findOrCreateActiveKid` → `ensureActiveGeneration` (INSERT tenant_key_material) | `backend/src/main/java/org/fabt/shared/security/KidRegistryService.java:88-107` (set_config at :100-101); INSERT at :151-155 via `ensureActiveGeneration` | **B** | `@Transactional` at :88; explicit `SELECT set_config('app.tenant_id', ?, true)` at :100 before `ensureActiveGeneration()` at :102 which runs the INSERT. Design note at :94-99 documents the Phase B rationale. |

## Table: `kid_to_tenant_key`

| Site | File:line | Mechanism | Notes |
|------|-----------|-----------|-------|
| `KidRegistryService.findOrCreateActiveKid` → `findOrCreateKid` (INSERT kid_to_tenant_key) | `backend/src/main/java/org/fabt/shared/security/KidRegistryService.java:88-107, 171-196` (set_config at :100-101; INSERT at :184-188) | **B** | Same `@Transactional` + `set_config` prologue as the `tenant_key_material` site above; the INSERT at `:184` runs inside the bound tx after `findOrCreateKid` is invoked from `findOrCreateActiveKid` at :104. |

## Gaps found — resolved 2026-04-17

1. **`AccessCodeService.validateCode` UPDATE on `one_time_access_code`** — `backend/src/main/java/org/fabt/auth/service/AccessCodeService.java:110`.
   - **Pre-auth code path** (`AuthController.accessCodeLogin` at `AuthController.java:241`). TenantContext is NOT bound; `app.tenant_id` is the empty-string default set by `RlsDataSourceConfig.applyRlsContext:129`.
   - **Impact:** V68's `otac_update_restrictive` policy evaluates `tenant_id = fabt_current_tenant_id()` where `fabt_current_tenant_id()` returns NULL — the predicate evaluates NULL for every row, so the UPDATE silently affected zero rows. The access-code flow then returned the user (hash matched) and issued a JWT, but `used=true` was never set in the DB → the same code could be replayed until `expires_at`. Live code-replay vector, not just an RLS rejection log line.
   - **Status: FIXED.** `validateCode` now runs `SELECT set_config('app.tenant_id', tenant.getId(), true)` inside the `@Transactional` method immediately before the UPDATE, binding for the scope of the current transaction. Classifies as **Mechanism B**. Test coverage: `TotpAndAccessCodeIntegrationTest` 19/19 green post-fix.
   - **Severity (pre-fix):** High. Phase-B rollout would have made the replay window reachable in production.

2. **`HmisPushService.createOutboxEntriesForTenant` INSERT on `hmis_outbox` (batch path)** — `backend/src/main/java/org/fabt/hmis/service/HmisPushService.java:136` when invoked from `HmisPushJobConfig.createOutboxTasklet` at `backend/src/main/java/org/fabt/analytics/batch/HmisPushJobConfig.java:89`.
   - **Batch path (Spring Batch tasklet → scheduler → TaskExecutor thread).** The tasklet did not wrap the call in `TenantContext.runWithContext`. The service method is `@TenantUnscoped` and received `tenantId` as a parameter but never bound it.
   - **Impact:** Under V68's `tenant_isolation_hmis_outbox` policy and V69 FORCE RLS, the INSERT at :136 would be rejected with 42501. Every scheduled HMIS push from the batch tasklet would log an error and the tenant's outbox entry would not be created — no HMIS data flowing to HUD vendors until fixed.
   - **Status: FIXED.** `createOutboxEntriesForTenant` now runs `SELECT set_config('app.tenant_id', tenantId, true)` at method entry (inside the existing `@Transactional` scope). Covers BOTH callers (batch tasklet AND `createOutboxEntriesForCurrentTenant` admin path) without changing the call sites. Classifies as **Mechanism B**. Test coverage: `HmisBridgeIntegrationTest` 14/14 green post-fix.
   - **Severity (pre-fix):** High for HMIS-producing tenants; zero impact on tenants without enabled vendors.

## Safe-by-inspection sites referenced during audit

- Flyway migrations (`db/migration/V59__...`, `V74__...`) INSERT into `audit_events`, `tenant_key_material`, and `kid_to_tenant_key`. Flyway runs as the schema-owner role which bypasses FORCE RLS (policy applies to `fabt_app` only). Out of scope per W-SYS-1 task prompt.

## Change log

| Date       | Change                                                    | Driver                       |
|------------|-----------------------------------------------------------|------------------------------|
| 2026-04-17 | Initial audit from warroom W-SYS-1.                       | Phase B review findings.     |
| 2026-04-17 | Two gaps (AccessCodeService.validateCode, HmisPushService.createOutboxEntriesForTenant) patched via Mechanism B — both now bind `app.tenant_id` via `set_config` inside their existing `@Transactional` method. | Same-day fix after audit surfaced them. |
