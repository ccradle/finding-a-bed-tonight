package org.fabt.auth.domain;

/**
 * Role assigned to a user (in {@code app_user.roles}) or a platform operator
 * (in platform JWTs from {@code platform_user}).
 *
 * <h2>Role taxonomy (post v0.53 / Phase G-4 / issue #141)</h2>
 *
 * <ul>
 *   <li>{@link #COC_ADMIN} — top role within a tenant. Manages tenant users,
 *       config, OAuth2 providers, API keys, observability settings, and other
 *       tenant-scoped admin operations. Cryptographically bounded by the
 *       per-tenant DEK signing the JWT (Phase A4 D25 cross-tenant cross-check).
 *       Can be combined with other roles in the same {@code roles} array.</li>
 *   <li>{@link #COORDINATOR} — manages shelter-side state (availability, DV
 *       referral acceptance/rejection). Scoped to assigned shelters within a
 *       tenant.</li>
 *   <li>{@link #OUTREACH_WORKER} — bed search + reservation requests +
 *       (with dvAccess) DV referral creation. Scoped to a tenant.</li>
 *   <li>{@link #PLATFORM_OPERATOR} — platform-spanning operations (tenant
 *       create/suspend/offboard/hardDelete, key rotation, HMIS exports,
 *       global batch jobs, OAuth2 connection probes). Issued ONLY from the
 *       separate {@code platform_user} table via {@code /auth/platform/login};
 *       carried by JWTs with {@code iss=fabt-platform} (no {@code tenantId}
 *       claim). Every PLATFORM_OPERATOR endpoint is additionally gated by
 *       the {@code @PlatformAdminOnly(reason, emits)} aspect that records to
 *       {@code platform_admin_access_log} + chained {@code audit_events}.</li>
 *   <li>{@link #PLATFORM_ADMIN} — <b>DEPRECATED.</b> Misnomer: implemented
 *       as "top role within a tenant" but read by reviewers as "platform-spanning
 *       super-admin." Replaced by {@link #COC_ADMIN} (tenant-scoped) and
 *       {@link #PLATFORM_OPERATOR} (genuinely platform-scoped, separate
 *       identity). The V87 migration backfilled COC_ADMIN onto every existing
 *       PLATFORM_ADMIN-bearing user record, preserving tenant-scoped access
 *       through the deprecation window. The cleanup release (post-v0.53)
 *       will REMOVE this enum value entirely. Do NOT assign to new users.
 *       New {@code @PreAuthorize} annotations referencing {@code 'PLATFORM_ADMIN'}
 *       will fail the {@code NoPlatformAdminPreauthorizeTest} ArchUnit guard
 *       added in G-4.4.</li>
 * </ul>
 *
 * <h2>Cross-tenant isolation</h2>
 *
 * <p>The cryptographic boundary is at the JWT signing layer, not the role
 * field. A {@code COC_ADMIN} of tenant A physically cannot present a valid
 * JWT to tenant B's endpoints — the tenant's DEK signed the JWT with a
 * tenant-specific kid, and the cross-tenant cross-check at
 * {@code JwtService:409-424} rejects mismatches with
 * {@code CrossTenantJwtException}. The deprecated {@link #PLATFORM_ADMIN}
 * had the same behavior; the rename to {@link #COC_ADMIN} reflects intent
 * without changing the cryptographic property.
 *
 * <p>{@link #PLATFORM_OPERATOR} JWTs route through a different validation
 * path: {@code iss=fabt-platform} resolves the kid against
 * {@code platform_key_material} instead of {@code jwt_key_generation}, and
 * the no-{@code tenantId}-claim is accepted (NOT a tenant-id sentinel —
 * separate validator branch, not loosened conditional).
 */
public enum Role {
    COC_ADMIN,
    COORDINATOR,
    OUTREACH_WORKER,
    PLATFORM_OPERATOR,

    /**
     * @deprecated since 0.53.0; replaced by {@link #COC_ADMIN} for
     * tenant-scoped admin and {@link #PLATFORM_OPERATOR} for platform-scoped
     * operations. Will be removed in the cleanup release after one
     * deprecation window. See class-level Javadoc for migration details.
     */
    @Deprecated(forRemoval = true, since = "0.53.0")
    PLATFORM_ADMIN
}
