# RLS Coverage Map

**Design D1 (cross-tenant-isolation-audit)**: the service layer is the system-of-record for tenant isolation. PostgreSQL Row Level Security is defense-in-depth for `dv_access` visibility gating — it does NOT enforce tenant isolation at the database layer. Tenant isolation is enforced by `findByIdAndTenantId` patterns in every service method that accesses tenant-owned data.

**Design D4**: RLS stays binary (`dv_access` / `app.current_user_id`), NOT tenant-scoped, for non-regulated tables. Tenant-scoped RLS on regulated tables (`audit_events`, `hmis_audit_log`) is deferred to the companion change `multi-tenant-production-readiness` (D14).

## Tables with RLS enabled

| Table | RLS Policy | What policy enforces | Tenant-isolated by | Cross-tenant test |
|---|---|---|---|---|
| `shelter` | `dv_shelter_access` | `dv_access` via session var — DV shelters visible only when `app.dv_access = 'true'` | Service: `ShelterService.findByIdOrThrow` | `CrossTenantIsolationTest.directObjectReference_returns404` |
| `bed_availability` | `dv_bed_availability_access` | `dv_access` inherited via shelter FK join | Service: `AvailabilityService.createSnapshot` (shelter pre-validated) | `CrossTenantIsolationTest.concurrentShelterListIsolation` |
| `reservation` | `dv_reservation_access` | `dv_access` inherited via shelter FK join | Service: `ReservationService` (shelter pre-validated) | Indirect via shelter isolation |
| `shelter_constraints` | `dv_shelter_constraints_access` | `dv_access` inherited via shelter FK join | Service: shelter pre-validated before constraints lookup (SAFE site) | Indirect via shelter isolation |
| `shelter_capacity` | `dv_shelter_capacity_access` | `dv_access` inherited via shelter FK join | Service: shelter pre-validated | Indirect via shelter isolation |
| `referral_token` | `dv_referral_token_access` | `dv_access` inherited via shelter FK join — **DOES NOT enforce tenant isolation** (corrected in V58, originally misleading in V21) | Service: `ReferralTokenService.findByIdOrThrow` (tenant + dv check) | `DvReferralIntegrationTest.tc_accept/reject_crossTenant_returns404` |
| `surge_event` | `surge_event_tenant_access` | `dv_access` check | Service: `SurgeEventService` | Indirect |
| `notification` | `notification_read/write_policy` | `recipient_id = app.current_user_id` — user-scoped, NOT tenant-scoped | Service: `NotificationPersistenceService` (recipient check) | `NotificationRlsIntegrationTest` |
| `escalation_policy` | `escalation_policy_read/insert` | `tenant_id` match OR platform-default (`tenant_id IS NULL`) | Service: `EscalationPolicyService.findCurrentByTenantAndEventType` | `EscalationPolicyEndpointTest` |

## Tenant-owned tables WITHOUT RLS

These tables rely exclusively on service-layer `findByIdAndTenantId` patterns (D1). Adding RLS is deferred to `multi-tenant-production-readiness` for regulated tables (D14).

| Table | Service-layer guard | Cross-tenant test |
|---|---|---|
| `app_user` | `UserService.getUser` (manual tenant check) | Multiple cross-tenant admin tests |
| `api_key` | `ApiKeyService.findByIdOrThrow` → `findByIdAndTenantId` | `ApiKeyAuthTest.tc_rotate/deactivate_crossTenant_returns404` |
| `tenant_oauth2_provider` | `TenantOAuth2ProviderService.findByIdOrThrow` → `findByIdAndTenantId` | `OAuth2ProviderTest.tc_update/delete/create_crossTenant` |
| `subscription` | `SubscriptionService.findByIdOrThrow` → `findByIdAndTenantId` | `SubscriptionIntegrationTest.tc_delete_crossTenant` |
| `webhook_delivery_log` | Via subscription tenant check | Indirect via subscription isolation |
| `audit_events` | `AuditEventService` (tenant_id filter, V57) | `AuditEventTenantIsolationTest` |
| `one_time_access_code` | FK-scoped via `app_user` (user looked up by tenant first) | `TotpAndAccessCodeIntegrationTest.tc_generateAccessCode_crossTenant` |
| `password_reset_token` | FK-scoped via `app_user` + SHA-256 token uniqueness | `EmailPasswordResetIntegrationTest.tc_resetPassword_emailCollides` |
| `hmis_outbox` | `HmisOutboxRepository` (tenant-scoped queries) | `HmisBridgeIntegrationTest` |
| `hmis_audit_log` | `HmisAuditRepository` (tenant-scoped queries) | Indirect |
| `import_log` | `ShelterImportService` (tenant from TenantContext) | Indirect |
| `user_oauth2_link` | `OAuth2AccountLinkService` (FK-scoped via user) | `OAuth2AccountLinkTest` |
| `totp_recovery` | Self-path (JWT-subject user_id) | Self-path tests |
| `coordinator_assignment` | FK-scoped via shelter + user (both tenant-owned) | Indirect |

## Infrastructure

- **`app.tenant_id` session variable**: set on every connection borrow by `RlsDataSourceConfig` (Phase 4.8, D13). No RLS policy currently reads it — installed as infrastructure for `multi-tenant-production-readiness` D14 realization.
- **`app.dv_access` session variable**: set on every connection borrow. Read by all `dv_*` RLS policies.
- **`app.current_user_id` session variable**: set on every connection borrow. Read by notification RLS policies.

## Cross-references

- **SAFE-sites registry**: `docs/security/safe-tenant-bypass-sites.md` — call sites verified safe despite bare `findById`
- **ArchUnit enforcement**: `TenantGuardArchitectureTest` (Family A + B)
- **SQL predicate coverage**: `TenantPredicateCoverageTest` (JSqlParser + JavaParser)
- **CONTRIBUTING.md**: tenant-owned table allowlist rule for new migrations
