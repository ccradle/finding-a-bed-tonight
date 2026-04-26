package org.fabt.auth.platform;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.fabt.shared.audit.AuditEventType;
import org.fabt.shared.web.TenantContext;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Phase G-4.3 — AOP aspect that wraps every {@code @PlatformAdminOnly}
 * controller method with a double-write to {@code platform_admin_access_log}
 * (PAL) plus a chained {@code audit_events} (AE) row inside one
 * {@code REQUIRES_NEW} transaction.
 *
 * <p><b>Decision 11 — pre-generated UUIDs:</b> both row ids are generated
 * client-side at aspect entry. PAL row carries {@code audit_event_id =
 * <pre-gen AE id>}; AE row carries {@code id = <pre-gen AE id>} +
 * {@code details.platform_admin_access_log_id = <pre-gen PAL id>}. The
 * resulting bidirectional linkage allows forensic queries from either side.
 *
 * <p><b>Decision 13 — HARD_DELETE tenant_id override:</b> when the action
 * is {@link AuditEventType#PLATFORM_TENANT_HARD_DELETED}, the AE row's
 * {@code tenant_id} is forced to {@link TenantContext#SYSTEM_TENANT_ID}
 * regardless of the controller method's {@code tenantId} parameter — the
 * tenant chain head is about to be cascade-deleted, so a chained AE row
 * pointing at the doomed tenant would either fail the FK or be removed by
 * the cascade.
 *
 * <p><b>Warroom D2 — body fingerprint, NEVER raw body:</b>
 * {@code request_body_excerpt} on the PAL row is
 * {@code "Content-Type=...;Content-Length=...;SHA-256=..."}. Forensic
 * readers correlate the SHA-256 against application logs (which already
 * redact sensitive fields per Phase A patterns).
 *
 * <p><b>Warroom D3 — no email in AE.details:</b> {@code platform_user_id}
 * only; downstream join against {@code platform_user} resolves the email
 * (or returns "anonymized" for GDPR Art-17 purged operators). Severs the
 * audit chain from the anonymization boundary.
 *
 * <p><b>Warroom D5 — MDC marker:</b> {@code MDC.put("platform_action",
 * "true")} at aspect entry; {@code MDC.remove(...)} in the {@code finally}
 * block. Every log line emitted during the proceeding method's execution
 * carries the marker for SOC SIEM filtering (G-4.5 alerts depend on it).
 *
 * <p><b>Audit-commits-before-method semantics</b> (Decision 11 + Casey
 * runbook note): the aspect's {@code REQUIRES_NEW} transaction commits
 * the PAL + AE rows BEFORE the business method runs. If the business
 * method then throws, the audit rows persist — capturing the ATTEMPT.
 * False-positive risk is accepted; runbook documents how to correlate
 * "attempted" vs "completed" via application logs at the same
 * {@code audit_event_id}.
 */
@Aspect
@Component
public class PlatformAdminLogger {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminLogger.class);

    private static final String MDC_PLATFORM_ACTION = "platform_action";
    private static final int JUSTIFICATION_EXCERPT_LIMIT = 200;
    private static final int REQUEST_BODY_EXCERPT_LIMIT = 2000;

    private final PlatformAdminAccessLogger writer;
    private final PlatformActionStateCapture stateCapture;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PlatformAdminLogger(PlatformAdminAccessLogger writer,
                                PlatformActionStateCapture stateCapture,
                                tools.jackson.databind.ObjectMapper objectMapper,
                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.writer = writer;
        this.stateCapture = stateCapture;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * AOP entry point. Runs {@code @Around} every method bearing
     * {@link PlatformAdminOnly}. The cut-point uses the annotation, not a
     * package pattern, so any controller anywhere can opt in just by adding
     * the annotation (subject to the ArchUnit guard requiring
     * {@code @PreAuthorize} on the same method).
     */
    @Around("@annotation(annotation)")
    public Object aroundPlatformAdminOnly(ProceedingJoinPoint pjp,
                                           PlatformAdminOnly annotation) throws Throwable {
        // Defense-in-depth gate #1 (G-4.5 §6.17): the JustificationValidationFilter
        // SHOULD have rejected any request missing X-Platform-Justification
        // before this aspect runs. If we observe the header missing here, the
        // filter chain is mis-configured (filter disabled, ordering broken,
        // or new path bypassing the filter). Increment the
        // FabtPlatformActionWithoutJustification counter — alert paged at
        // any non-zero rate. Reject defensively so the bug condition does
        // not leak an unaudited platform action.
        HttpServletRequest req = currentRequest();
        if (req != null && (req.getHeader("X-Platform-Justification") == null
                || req.getHeader("X-Platform-Justification").isBlank())) {
            emitWithoutJustificationMetric(annotation.emits());
            log.error("Platform admin endpoint reached aspect without X-Platform-Justification — "
                    + "JustificationValidationFilter is mis-configured. action={}",
                    annotation.emits());
            throw new org.springframework.security.access.AccessDeniedException(
                    "Platform admin endpoints require X-Platform-Justification header");
        }

        // Defense-in-depth gate #2 (F2 / G-4.4 task 5.4b): the SecurityContext
        // authentication MUST carry the MFA_VERIFIED marker authority added
        // by JwtAuthenticationFilter when binding a post-MFA platform JWT.
        // If absent, reject BEFORE writing any audit rows. This catches a
        // future regression in JwtAuthenticationFilter that might bind
        // ROLE_PLATFORM_OPERATOR without requiring mfaVerified.
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getAuthorities().stream()
                .noneMatch(a -> "MFA_VERIFIED".equals(a.getAuthority()))) {
            log.warn("Platform admin endpoint hit without MFA_VERIFIED authority — "
                    + "rejecting before audit write. action={}", annotation.emits());
            throw new org.springframework.security.access.AccessDeniedException(
                    "Platform admin endpoints require MFA-verified platform JWT");
        }

        String previousMdc = MDC.get(MDC_PLATFORM_ACTION);
        MDC.put(MDC_PLATFORM_ACTION, "true");
        UUID palId = UUID.randomUUID();
        UUID auditEventId = UUID.randomUUID();
        boolean auditCommitted = false;
        try {
            commitAuditRows(pjp, annotation, palId, auditEventId);
            auditCommitted = true;
            return pjp.proceed();
        } catch (Throwable methodFailure) {
            if (auditCommitted) {
                emitMetric(annotation.emits(), "method_failed_after_audit");
                log.warn("Platform admin method threw AFTER audit commit — "
                                + "PAL+AE rows persist; investigate completion via "
                                + "audit_event_id={}, action={}",
                        auditEventId, annotation.emits());
            } else {
                // Audit commit itself failed (RLS rejection, FK violation,
                // chain-hash mismatch, etc.). The business method did NOT
                // run; the operator gets a 5xx via the controller's
                // exception handler. Per Casey's chain-of-custody
                // directive, an action that cannot be audited MUST NOT
                // proceed — but the operator-facing response loses the
                // forensic link. Emit a structured WARN with the
                // pre-generated audit_event_id so ops can correlate this
                // failure across application logs even though no AE row
                // ever committed.
                emitMetric(annotation.emits(), "aspect_failed");
                log.warn("Platform admin aspect FAILED to commit audit rows — "
                                + "action denied. audit_event_id={} (NOT persisted), "
                                + "pal_id={} (NOT persisted), action={}, cause={}",
                        auditEventId, palId, annotation.emits(),
                        methodFailure.toString());
            }
            throw methodFailure;
        } finally {
            // Restore prior MDC value (or remove entirely if there was none).
            if (previousMdc == null) {
                MDC.remove(MDC_PLATFORM_ACTION);
            } else {
                MDC.put(MDC_PLATFORM_ACTION, previousMdc);
            }
        }
    }

    /**
     * Resolves request context from the join point, then delegates to the
     * separate {@link PlatformAdminAccessLogger} bean — which is itself
     * {@code @Transactional(REQUIRES_NEW)}, so the cross-bean call goes
     * through the Spring proxy and the transactional boundary engages.
     * If this method were {@code @Transactional} on the same bean, the
     * self-invocation from {@link #aroundPlatformAdminOnly} would silently
     * bypass the proxy (Phase B Bug A+D failure mode).
     */
    private void commitAuditRows(ProceedingJoinPoint pjp, PlatformAdminOnly annotation,
                                  UUID palId, UUID auditEventId) {
        UUID platformUserId = currentPlatformUserId();
        HttpServletRequest request = currentRequest();
        UUID actionTenantId = resolveActionTenantId(pjp, annotation);
        String requestPath = request != null ? request.getRequestURI() : "(no-request)";
        String requestMethod = request != null ? request.getMethod() : "?";
        String ipAddress = request != null ? request.getRemoteAddr() : null;
        String justificationHeader = request != null
                ? request.getHeader("X-Platform-Justification")
                : null;
        String justification = annotation.reason()
                + " | request: "
                + (justificationHeader == null ? "" : justificationHeader);
        String bodyExcerpt = computeBodyExcerpt(request);
        UUID resourceId = resolveResourceId(pjp);
        String resource = resolveResourceName(annotation.emits());

        // Drain captured before-state from the request-scoped bean.
        // captureBefore() must be called by the controller pre-proceed
        // for the field to land in PAL; null is fine when there's no
        // meaningful state delta (e.g. read-only platform actions).
        String beforeStateJson = serializeStateOrNull(stateCapture.getBeforeState());

        writer.writePlatformAction(
                palId,
                auditEventId,
                platformUserId,
                actionTenantId,
                annotation.emits(),
                resource,
                resourceId,
                truncate(justification, REQUEST_BODY_EXCERPT_LIMIT),
                requestMethod,
                requestPath,
                bodyExcerpt,
                beforeStateJson,
                ipAddress);

        emitMetric(annotation.emits(), "committed");
    }

    /**
     * Decision 13 + tenant-id resolution: if the action is HARD_DELETE,
     * force {@link TenantContext#SYSTEM_TENANT_ID} regardless of method
     * parameters. Otherwise, look for a parameter named {@code tenantId}
     * of type {@link UUID} in the proceeding method signature; default to
     * SYSTEM_TENANT_ID if not present.
     */
    private static UUID resolveActionTenantId(ProceedingJoinPoint pjp, PlatformAdminOnly annotation) {
        if (annotation.emits() == AuditEventType.PLATFORM_TENANT_HARD_DELETED) {
            return TenantContext.SYSTEM_TENANT_ID;
        }
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Parameter[] params = method.getParameters();
        Object[] args = pjp.getArgs();
        for (int i = 0; i < params.length; i++) {
            if ("tenantId".equals(params[i].getName())
                    && UUID.class.isAssignableFrom(params[i].getType())
                    && args[i] != null) {
                return (UUID) args[i];
            }
        }
        return TenantContext.SYSTEM_TENANT_ID;
    }

    private static UUID resolveResourceId(ProceedingJoinPoint pjp) {
        Method method = ((MethodSignature) pjp.getSignature()).getMethod();
        Parameter[] params = method.getParameters();
        Object[] args = pjp.getArgs();
        // Prefer 'id' as the canonical resource-id parameter; fall back to
        // 'tenantId' if no 'id' is in the signature.
        for (int i = 0; i < params.length; i++) {
            if ("id".equals(params[i].getName())
                    && UUID.class.isAssignableFrom(params[i].getType())
                    && args[i] != null) {
                return (UUID) args[i];
            }
        }
        for (int i = 0; i < params.length; i++) {
            if ("tenantId".equals(params[i].getName())
                    && UUID.class.isAssignableFrom(params[i].getType())
                    && args[i] != null) {
                return (UUID) args[i];
            }
        }
        return null;
    }

    private static String resolveResourceName(AuditEventType action) {
        return switch (action) {
            case PLATFORM_TENANT_CREATED, PLATFORM_TENANT_SUSPENDED,
                    PLATFORM_TENANT_UNSUSPENDED, PLATFORM_TENANT_OFFBOARDED,
                    PLATFORM_TENANT_HARD_DELETED, PLATFORM_KEY_ROTATED,
                    PLATFORM_HMIS_EXPORTED, PLATFORM_OAUTH2_TESTED -> "tenant";
            case PLATFORM_BATCH_JOB_TRIGGERED -> "batch_job";
            case PLATFORM_TEST_RESET_INVOKED -> "test_reset";
            case PLATFORM_USER_LOCKED_OUT, PLATFORM_USER_CREATED,
                    PLATFORM_USER_RESET_TO_BOOTSTRAP -> "platform_user";
            default -> null;
        };
    }

    /**
     * Returns the platform_user UUID from the SecurityContext authentication,
     * which the {@code JwtAuthenticationFilter} bound to the platform JWT's
     * {@code sub} claim. Returns null only if the aspect somehow runs before
     * Spring Security — defense-in-depth ArchUnit rule prevents
     * {@code @PlatformAdminOnly} without {@code @PreAuthorize} so this
     * branch is unreachable in practice.
     */
    private static UUID currentPlatformUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getPrincipal() == null) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof UUID u) {
            return u;
        }
        try {
            return UUID.fromString(principal.toString());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes sra) {
            return sra.getRequest();
        }
        return null;
    }

    /**
     * Decision D2: body fingerprint, NEVER raw content. Current format:
     * {@code "Content-Type=...;Content-Length=..."}.
     *
     * <p><b>SHA-256 deliberately omitted (warroom M-RV4):</b> by the time
     * this aspect runs, the servlet input stream has been consumed by
     * Spring's {@code @RequestBody} deserializer — body bytes are no
     * longer available. Emitting a SHA-256 of the empty string would be a
     * stable-but-meaningless constant for every PAL row (the empty-string
     * SHA hash, {@code e3b0c44...}), giving false confidence that
     * different requests can be distinguished by their fingerprint.
     *
     * <p>Forensic correlation continues to work via the {@code audit_event_id}
     * link to application logs (which already redact sensitive fields per
     * Phase A patterns). Body-content reconstruction is NOT part of v0.53's
     * compliance posture; capture as design follow-up F8 if a future
     * compliance review requires it (would need a {@code ContentCachingRequestWrapper}
     * filter earlier in the chain to preserve body bytes for the aspect).
     */
    private String computeBodyExcerpt(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String contentType = request.getContentType();
        int contentLength = request.getContentLength();
        String excerpt = "Content-Type=" + (contentType == null ? "" : contentType)
                + ";Content-Length=" + (contentLength < 0 ? "" : contentLength);
        return truncate(excerpt, REQUEST_BODY_EXCERPT_LIMIT);
    }

    private static String truncate(String input, int max) {
        if (input == null) {
            return null;
        }
        return input.length() <= max ? input : input.substring(0, max);
    }

    /**
     * Serializes a state map to a JSON string suitable for the {@code JSONB}
     * column. Returns {@code null} for empty/missing input so the column
     * stays NULL (the V89 size CHECK only applies to non-NULL values).
     *
     * <p>If serialization fails or the result exceeds the V89 size cap
     * (64KB), returns {@code null} with a WARN log — better to drop the
     * captured state than to fail the audit write entirely.
     */
    private String serializeStateOrNull(java.util.Map<String, Object> state) {
        if (state == null || state.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(state);
            if (json.length() > 65000) {
                log.warn("PlatformActionStateCapture state serialized to {} bytes — "
                        + "above the V89 64KB CHECK; dropping to keep the audit write "
                        + "successful.", json.length());
                return null;
            }
            return json;
        } catch (Exception e) {
            log.warn("PlatformActionStateCapture state failed to serialize as JSON: {}",
                    e.toString());
            return null;
        }
    }

    private void emitMetric(AuditEventType action, String outcome) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("fabt.platform.admin.action")
                .tag("action", action.name())
                .tag("outcome", outcome)
                .register(meterRegistry)
                .increment();
    }

    /**
     * G-4.5 §6.17: defense-in-depth counter — incremented when the aspect
     * sees a request without {@code X-Platform-Justification}, which
     * indicates the {@code JustificationValidationFilter} chain is broken
     * (filter disabled, ordering rearranged, or a future code path bypasses
     * the filter). The counter SHOULD be zero in steady state — any
     * non-zero rate fires {@code FabtPlatformActionWithoutJustification}.
     */
    private void emitWithoutJustificationMetric(AuditEventType action) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder("fabt.platform.action.without_justification")
                .tag("action", action.name())
                .register(meterRegistry)
                .increment();
    }

    /**
     * Avoid the proxy-self-invocation pitfall: callers from outside this
     * aspect that need the same write surface go through
     * {@link PlatformAdminAccessLogger#logLockout} instead, which is its
     * own Spring bean with its own {@code @Transactional} proxy.
     */
}
