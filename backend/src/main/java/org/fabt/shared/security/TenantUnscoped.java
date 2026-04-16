package org.fabt.shared.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method as an intentional exception to the project's tenant-guard
 * convention. Used ONLY when a service or controller method must call
 * {@code Repository.findById(UUID)} or {@code Repository.existsById(UUID)} on
 * a tenant-owned table without routing through a {@code findByIdAndTenantId}
 * variant — typically batch jobs, scheduled expirers, or reconciliation
 * tasklets that require platform-wide visibility by design.
 *
 * <p>The justification string is required and must be non-empty. It becomes
 * the author's future-self documentation: why this method is safe to bypass
 * the guard, and what invariant keeps it safe. Reviewers read it during code
 * review; the ArchUnit rule enforces its presence.
 *
 * <p>Paired with {@code TenantGuardArchitectureTest}, which scans every class
 * in {@code org.fabt.*.service} and {@code org.fabt.*.api} and fails the
 * build when a bare {@code findById(UUID)}/{@code existsById(UUID)} call on
 * a tenant-owned repository appears without this annotation on the calling
 * method or routing through a tenant-scoped repository method.
 *
 * <p>See the {@code cross-tenant-isolation-audit} OpenSpec change (design
 * decision D2) for the rationale and full contract.
 *
 * <h2>Example</h2>
 * <pre>{@code
 * @TenantUnscoped("system-scheduled reservation expiry needs platform-wide visibility; tenant context is set from the fetched row")
 * public void expireReservation(UUID reservationId) {
 *     Reservation reservation = reservationRepository.findById(reservationId).orElseThrow();
 *     // ... tenant-context set from reservation.getTenantId() before any further work ...
 * }
 * }</pre>
 *
 * <h2>Non-goals</h2>
 * <ul>
 *   <li>This annotation does NOT grant the annotated method platform-admin
 *       privileges at runtime. Authorization still flows through Spring
 *       Security {@code @PreAuthorize} and JWT filters.</li>
 *   <li>This annotation does NOT disable RLS for the connection. RLS
 *       enforcement (where applicable) is orthogonal to the tenant-guard
 *       convention.</li>
 *   <li>This annotation is NOT a substitute for proper auditing. A method
 *       that intentionally reaches across tenants should still emit audit
 *       events per the normal contract.</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantUnscoped {

    /**
     * The justification string — human-readable explanation of why this
     * method is safe to call a tenant-scoped repository method without a
     * tenant predicate. Must be non-empty at build time (enforced by
     * {@code TenantGuardArchitectureTest}).
     *
     * <p>Named {@code value} (not {@code justification}) so callers can use
     * the positional shorthand: {@code @TenantUnscoped("explanation")}
     * rather than {@code @TenantUnscoped(value = "explanation")}. This
     * matches Java convention for single-element annotations such as
     * {@code @SuppressWarnings("unchecked")}.
     *
     * @return the justification string
     */
    String value();
}
