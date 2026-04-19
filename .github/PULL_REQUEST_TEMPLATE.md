<!-- Delete sections that don't apply. -->

## Summary

<!-- 1-3 bullets: what changed and why. -->

## Test plan

<!-- bulleted markdown checklist of TODOs for testing the PR -->

## Security + tenant-isolation review gates

If this PR modifies any tenant-isolation-adjacent surface, confirm the relevant
gates below. Unchecked boxes without deliberate justification may block review.

- [ ] **No new `@TenantUnscoped`** introductions (bypasses Family A tenant-guard
      rule). If new usage is required, confirm the justification string explains
      why the annotated method is safe to call a tenant-owned repository without
      a tenant predicate, and request `@ccradle` review.
- [ ] **No new `@TenantUnscopedCache`** introductions (Phase C Family C, task
      4.2+4.3). If this PR introduces a new `@TenantUnscopedCache`, I confirm
      the justification explains why tenant scoping would be incorrect for this
      cache (e.g., key is globally unique, pre-auth, or carries tenant discriminator
      structurally). The CI grep job will auto-request `@ccradle` review.
- [ ] **No new `@TenantScopedByConstruction`** introductions (Phase C Family C).
      If added, confirm both (a) the cache key includes `tenantId` (composite
      key record or tenantId-typed key) AND (b) the read path has an explicit
      `tenantId`-match check that emits `CROSS_TENANT_*` audit + counter on
      mismatch. The CI grep job will auto-request `@ccradle` review.
- [ ] **No new `FamilyCArchitectureTest.PENDING_MIGRATION_SITES` entries.**
      Task 4.b migrates the existing allowlist entries to
      `TenantScopedCacheService`; adding NEW entries defeats the point of the
      rule. If you added a new `CacheService.get/put/evict` call site from a
      service/api/security/auth-package class, route it through
      `TenantScopedCacheService` instead of adding an allowlist exemption.

## Flyway / RLS

- [ ] No new Flyway migrations OR new migrations are above HWM and carry
      `@tenant-safe` / `@tenant-destructive` comments.
- [ ] No `SECURITY DEFINER` in SQL migrations (blocked by `MigrationLintTest`).
- [ ] If RLS policies change: `docs/security/pg-policies-snapshot.md` +
      `deploy/release-gate-pins.txt` updated.

## Legal

- [ ] No overclaiming language ("compliant", "guarantees", "equivalent to") in
      code comments, docs, or UI strings — use "designed to support" per
      `feedback_legal_claims_review.md`.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
