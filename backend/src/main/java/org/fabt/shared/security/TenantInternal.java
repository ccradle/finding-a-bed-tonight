package org.fabt.shared.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method or class as legitimately bypassing the F-3 tenant-state read-path
 * guard. Used by paths that cannot rely on the active-state check because they run
 * either before a tenant is fully ACTIVE (e.g. {@code TenantLifecycleService.create}
 * during bootstrap) or deliberately across all tenant states (e.g. the lifecycle
 * service's own state transitions, batch-job cleanup sweeps, crypto-shred paths).
 *
 * <p>Every use MUST include a justification string explaining why the bypass is safe.
 * The ArchUnit Family D rule ({@code TenantGuardArchitectureTest}) reads this annotation
 * to allow public methods to call {@code findByIdAndTenantId} without swapping to the
 * {@code findByIdAndActiveTenantId} variant. Think of it as the {@code SAFE_SITES}
 * allowlist's inline analogue — an annotation-local justification instead of a central
 * registry.</p>
 *
 * <p>Analogous to {@link TenantUnscoped} (which marks code that legitimately spans
 * multiple tenants). {@code @TenantInternal} is for code that operates on ONE tenant's
 * data but in a lifecycle state where the ACTIVE gate would be wrong.</p>
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface TenantInternal {
    /**
     * Free-form justification explaining why this method/class legitimately bypasses
     * the ACTIVE-state read guard. Required — a call site without a reason is
     * indistinguishable from a forgotten migration.
     */
    String value();
}
