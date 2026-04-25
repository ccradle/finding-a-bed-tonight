package org.fabt.auth.platform;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.fabt.shared.audit.AuditEventType;

/**
 * Marks a controller method as a platform-operator-only action that MUST
 * be audited via {@code platform_admin_access_log} (PAL) plus a chained
 * {@code audit_events} row.
 *
 * <p>Per design Decision 9: BOTH members ({@code reason} + {@code emits})
 * are required at the call site — no defaults. The {@code reason} is
 * boilerplate developer documentation explaining WHY the endpoint is
 * platform-only (e.g. "tenant lifecycle suspend — requires platform
 * authority because it affects every user in the target tenant"). It
 * lands in the PAL row as part of the {@code justification} string and
 * appears in audit reviews. The {@code emits} value drives the
 * {@code audit_events.action} column for the chained AE row, picked from
 * the {@link AuditEventType} closed set so typo-safety is enforced at
 * compile time.
 *
 * <h2>Two enforcement layers</h2>
 *
 * <p>This annotation is enforced by two separate Spring components:
 * <ol>
 *   <li>{@code JustificationValidationFilter} (servlet filter, pre-Security)
 *       — rejects 400 if the request is missing the
 *       {@code X-Platform-Justification} header or if the value is shorter
 *       than 10 chars. The filter resolves the request to its handler
 *       method via {@code RequestMappingHandlerMapping} and inspects this
 *       annotation. Skips for non-{@code @PlatformAdminOnly} paths.</li>
 *   <li>{@code PlatformAdminLogger} (Spring AOP aspect, post-Security) —
 *       runs {@code @Around} every method bearing this annotation. Inside a
 *       single {@code REQUIRES_NEW} transaction: pre-generates UUIDs for
 *       PAL + AE, INSERTs both rows linked by id, COMMITs, then calls
 *       {@code proceed()} so the business method runs. Per Decision 11,
 *       the audit row commits BEFORE the business method executes — an
 *       audit row indicates an ATTEMPTED action; correlate with
 *       application logs at the same {@code audit_event_id} for actual
 *       completion.</li>
 * </ol>
 *
 * <h2>Defense-in-depth invariants enforced by ArchUnit</h2>
 *
 * <ul>
 *   <li>Every method bearing this annotation MUST also bear
 *       {@code @PreAuthorize("hasRole('PLATFORM_OPERATOR')")} —
 *       the audit aspect must NEVER fire for an unauthenticated /
 *       wrong-role caller.</li>
 *   <li>Every method bearing this annotation MUST be in a
 *       {@code ..api..} package — the aspect is a controller-layer
 *       concern; service-layer audit annotations are out of scope (the
 *       lockout direct-write hook in {@code PlatformAuthService} is the
 *       documented exception).</li>
 * </ul>
 *
 * <p>Annotation lives in the {@code org.fabt.auth.platform} package so it
 * is co-located with the aspect that consumes it. Controllers in any
 * package can apply it.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PlatformAdminOnly {

    /**
     * Developer-facing rationale for why the endpoint is platform-only.
     * Appears in the PAL row's {@code justification} column prefixed to
     * the operator-supplied {@code X-Platform-Justification} header value.
     * Should explain the authority requirement in 1-2 sentences.
     */
    String reason();

    /**
     * The {@link AuditEventType} value the aspect writes to the chained
     * {@code audit_events.action} column for this endpoint. Picked from
     * the closed enum set so a typo is a compile error.
     */
    AuditEventType emits();
}
