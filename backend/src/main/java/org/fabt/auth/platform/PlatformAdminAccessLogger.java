package org.fabt.auth.platform;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.fabt.shared.audit.AuditEventRecord;
import org.fabt.shared.audit.AuditEventService;
import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.web.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase G-4.3 — write surface for {@code platform_admin_access_log} (PAL)
 * + chained {@code audit_events} (AE) rows. Two callers:
 *
 * <ol>
 *   <li>{@link PlatformAdminLogger} aspect — for every method bearing
 *       {@code @PlatformAdminOnly}, the aspect resolves request context
 *       from the {@code ProceedingJoinPoint} and delegates to
 *       {@link #writePlatformAction} here. The aspect cannot mark its own
 *       method {@code @Transactional} because Spring's
 *       proxy-self-invocation rule ({@code AOPProxy} only intercepts
 *       cross-bean calls) would silently bypass the transactional
 *       boundary.</li>
 *
 *   <li>{@link PlatformAuthService#recordFailureAndMaybeLock} — when the
 *       per-account auto-lockout threshold trips (V88 record_failure
 *       returns true), the lockout fires from an internal service path
 *       the aspect cannot reach. The service calls
 *       {@link #logLockout(UUID)} directly. Same write-surface, no
 *       request context (caller-supplied operator id).</li>
 * </ol>
 *
 * <p>All writes run in a single {@code REQUIRES_NEW} transaction so the
 * audit row commits independently of the calling business code (Decision
 * 11 — audit captures the ATTEMPT, runbook documents how to verify
 * completion via correlated application logs).
 */
@Service
public class PlatformAdminAccessLogger {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminAccessLogger.class);

    private final JdbcTemplate jdbc;
    private final AuditEventService auditEventService;

    public PlatformAdminAccessLogger(JdbcTemplate jdbc, AuditEventService auditEventService) {
        this.jdbc = jdbc;
        this.auditEventService = auditEventService;
    }

    /**
     * Writes one PAL row + one chained AE row in a {@code REQUIRES_NEW}
     * transaction. Called by {@link PlatformAdminLogger#aroundPlatformAdminOnly}
     * via Spring proxy so the {@code @Transactional} engages.
     *
     * @param palId            pre-generated PAL row id (Decision 11)
     * @param auditEventId     pre-generated AE row id (Decision 11)
     * @param platformUserId   actor — the platform_user that triggered the action
     * @param actionTenantId   tenant context for the AE row (SYSTEM_TENANT_ID
     *                         for platform-wide actions or HARD_DELETE override)
     * @param action           the {@link AuditEventType} written to AE.action
     *                         and PAL.action columns
     * @param resource         coarse resource label for PAL (e.g. "tenant")
     * @param resourceId       resource UUID where applicable
     * @param justification    full PAL justification string
     * @param requestMethod    HTTP method
     * @param requestPath      HTTP path
     * @param requestBodyExcerpt body fingerprint (Content-Type + Content-Length
     *                         + SHA-256), NEVER raw body content (D2)
     * @param ipAddress        request client ip (audit_events.ip_address)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void writePlatformAction(UUID palId,
                                     UUID auditEventId,
                                     UUID platformUserId,
                                     UUID actionTenantId,
                                     AuditEventType action,
                                     String resource,
                                     UUID resourceId,
                                     String justification,
                                     String requestMethod,
                                     String requestPath,
                                     String requestBodyExcerpt,
                                     String beforeStateJson,
                                     String ipAddress) {
        // 1. INSERT PAL — single table, raw JdbcTemplate.
        // before_state is populated from PlatformActionStateCapture if the
        // controller called captureBefore() before the action runs. Per F13,
        // after_state is always NULL on v0.53 — Decision 11's pre-proceed
        // commit timing + the V89 append-only trigger together mean post-
        // action state can't reach this row.
        jdbc.update(
                "INSERT INTO platform_admin_access_log ("
                        + "id, platform_user_id, action, resource, resource_id, "
                        + "justification, request_method, request_path, "
                        + "request_body_excerpt, before_state, after_state, "
                        + "audit_event_id) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, NULL, ?)",
                palId,
                platformUserId,
                action.name(),
                resource,
                resourceId,
                justification,
                requestMethod,
                requestPath,
                requestBodyExcerpt,
                beforeStateJson,
                auditEventId);

        // 2. INSERT AE via the pre-assigned-id audit subsystem path.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("platform_admin_access_log_id", palId.toString());
        // D3: NO platform_user_email — only the id. Downstream join resolves.
        details.put("platform_user_id",
                platformUserId == null ? null : platformUserId.toString());
        details.put("justification_excerpt", truncate(justification, 200));
        details.put("request_method", requestMethod);
        details.put("request_path", requestPath);

        AuditEventRecord event = new AuditEventRecord(
                platformUserId, null, action, details, ipAddress);
        auditEventService.persistPlatformAdminAuditEvent(auditEventId, actionTenantId, event);
    }

    /**
     * Direct-write hook (warroom D6) for the per-account auto-lockout
     * transition. Called from {@link PlatformAuthService#recordFailureAndMaybeLock}
     * when V88's {@code platform_user_record_failure} returns {@code true}
     * (this call tripped the threshold).
     *
     * <p>No request context is captured — the lockout fires inside an
     * internal service path with no controller method, no
     * {@code X-Platform-Justification} header, no body. The PAL row's
     * {@code justification} is a synthetic system message; ip_address is
     * pulled from {@link RequestContextHolder} when available (the
     * lockout is ALWAYS triggered by an HTTP request from the operator
     * trying to log in, so the request is in scope), null otherwise.
     *
     * <p><b>Transaction posture (warroom A-RV1):</b> {@code @Transactional}
     * lives on this method (the public entry point) — NOT on the inner
     * {@link #writePlatformAction} call alone. Reason: when this method
     * runs, it calls {@link #writePlatformAction} via {@code this.foo()}
     * self-invocation, which bypasses the Spring AOP proxy. Without
     * {@code @Transactional} on the entry point, REQUIRES_NEW would never
     * engage on the lockout path → {@code set_config('app.tenant_id', ...)}
     * inside the audit persister would run in an implicit single-statement
     * tx, revert before the INSERT, and FORCE RLS would reject. With the
     * annotation here, the cross-bean call from {@code PlatformAuthService}
     * hits the proxy, REQUIRES_NEW opens a fresh tx covering both INSERTs,
     * commits independently of any caller tx (there is none on this path).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logLockout(UUID platformUserId) {
        UUID palId = UUID.randomUUID();
        UUID auditEventId = UUID.randomUUID();

        String ipAddress = null;
        if (org.springframework.web.context.request.RequestContextHolder
                .getRequestAttributes() instanceof
                org.springframework.web.context.request.ServletRequestAttributes sra) {
            ipAddress = sra.getRequest().getRemoteAddr();
        }

        try {
            writePlatformAction(
                    palId,
                    auditEventId,
                    platformUserId,
                    TenantContext.SYSTEM_TENANT_ID,  // platform-wide; not chained
                    AuditEventType.PLATFORM_USER_LOCKED_OUT,
                    "platform_user",
                    platformUserId,
                    "PLATFORM_USER_LOCKED_OUT auto-lockout — 5 failed MFA attempts in 15 minutes (V88 policy)",
                    "INTERNAL",
                    "/auth/platform/login/mfa-verify",
                    null,
                    null,  // before_state — no meaningful pre-action state for an internal lockout
                    ipAddress);
        } catch (RuntimeException e) {
            // Per Marcus warroom synthesis: failure to audit a lockout is
            // a serious security signal but MUST NOT block the lockout
            // itself — the operator's account is already locked at this
            // point (V88 record_failure ran the SET account_locked=true
            // UPDATE). Log + swallow so the locked operator gets the
            // 401, then ops can detect the missing audit row via the
            // PAL.timestamp gap alert (G-4.5 task).
            //
            // §6.18: MDC marker so SOC platform_action filters pick this
            // line up alongside the @PlatformAdminOnly aspect's logs.
            // The lockout audit path runs OUTSIDE the aspect (service-
            // internal), so the aspect's MDC.put never wraps this call.
            // Set + remove in a try/finally so the marker is contained.
            org.slf4j.MDC.put("platform_action", "true");
            try {
                log.error("Failed to persist lockout audit row for platform_user {} — "
                                + "account remains locked, but audit chain is missing this entry. "
                                + "Investigate: {}",
                        platformUserId, e.getMessage(), e);
            } finally {
                org.slf4j.MDC.remove("platform_action");
            }
        }
    }

    private static String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }
}
