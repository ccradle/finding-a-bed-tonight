# SAFE Tenant-Bypass Sites Registry

Maintained by the `cross-tenant-isolation-audit` (Issue #117). Authoritative source: the `SAFE_SITES` set in `TenantGuardArchitectureTest.java`. This document is a human-readable companion — update both when adding a new entry.

## What this is

Every method in `org.fabt.*.service` or `org.fabt.*.api` that calls `Repository.findById(UUID)` on a tenant-owned table must either:

1. Route through `findByIdAndTenantId(UUID, UUID)` — explicit tenant guard, OR
2. Appear in this registry with a justification — verified safe by a different mechanism.

ArchUnit rule Family A enforces this at build time. Adding a new entry requires code review.

## Registry

| Site | Repository | Justification |
|---|---|---|
| `AuthController.refresh` | UserRepository | Self-path: userId from JWT subject (`auth.getName()`), not URL/body. User can only act on their own record. |
| `AuthController.verifyTotp` | UserRepository | Self-path: same JWT-subject-keyed pattern. |
| `PasswordController.changePassword` | UserRepository | Self-path: userId from JWT. |
| `PasswordController.resetPassword` | UserRepository | Admin-path: userId from JWT + admin role; `userService.getUser` applies manual tenant check. |
| `TotpController.enrollTotp` | UserRepository | Self-path: JWT-subject userId. |
| `TotpController.confirmTotpEnrollment` | UserRepository | Self-path: JWT-subject userId. |
| `TotpController.regenerateRecoveryCodes` | UserRepository | Self-path: JWT-subject userId. |
| `OAuth2AccountLinkService.linkOrReject` | UserRepository | FK-chain: existing OAuth2 link → `user_id` → tenant. Link was created under the correct tenant during initial email-match. |
| `PasswordResetService.resetPassword` | UserRepository | Token-hash-keyed: SHA-256 token is globally unique; the token row's `user_id` dictates tenant. No tenant in the request. |
| `UserService.findById` | UserRepository | Tenant-scoped wrapper: calls `findById` then manual `tenantId` equality check. This IS the tenant guard — callers delegate here. |
| `UserService.getUser` | UserRepository | Same as `findById` — throws `NoSuchElementException` on mismatch (maps to 404). |
| `AvailabilityService.createSnapshot` | ShelterConstraintsRepository | Pre-validated: `shelterId` from a prior `shelterService.findById` call which is tenant-scoped. |
| `BedSearchService.doSearch` | ShelterConstraintsRepository | Loop context: iterating shelters returned by a tenant-scoped search query. |
| `ShelterService.getDetail` | ShelterConstraintsRepository | Pre-validated: same pattern — shelter tenant-checked first. |
| `ShelterService.update` | ShelterConstraintsRepository | Pre-validated: same pattern. |
| `NotificationPersistenceService.markActed` | NotificationRepository | Self-path: asserts `recipientId == caller's userId` (JWT-derived). |
| `SubscriptionService.findRecentDeliveries` | SubscriptionRepository | Manual tenant check: `findById` then `!subscription.getTenantId().equals(tenantId)` → 404. Migrating to `findByIdAndTenantId` in companion change. |

## Cross-references

- **ArchUnit enforcement**: `TenantGuardArchitectureTest.java` (Phase 3.5)
- **SQL predicate coverage**: `TenantPredicateCoverageTest.java` (Phase 2.13)
- **RLS coverage map**: `docs/security/rls-coverage.md` (Phase 4.2)
- **Design decisions**: D1 (service-layer is source-of-truth), D2 (`@TenantUnscoped` annotation), D3 (404-not-403)
