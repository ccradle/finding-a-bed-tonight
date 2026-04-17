package org.fabt.shared.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;
    private final MeterRegistry meterRegistry;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    public GlobalExceptionHandler(MessageSource messageSource, MeterRegistry meterRegistry,
                                   org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.messageSource = messageSource;
        this.meterRegistry = meterRegistry;
        this.eventPublisher = eventPublisher;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        String path = ex.getResourcePath();
        String firstSegment = extractFirstPathSegment(path);
        Counter.builder("fabt.http.not_found.count")
                .tag("path_prefix", firstSegment)
                .tag("tenant_id", TenantContext.tenantTag())
                .register(meterRegistry)
                .increment();
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", "The requested resource was not found", 404));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String role = (auth != null && auth.getAuthorities() != null)
                ? auth.getAuthorities().stream().findFirst().map(Object::toString).orElse("none")
                : "anonymous";
        String path = extractFirstPathSegment(
                org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes()
                        instanceof org.springframework.web.context.request.ServletRequestAttributes sra
                        ? sra.getRequest().getRequestURI() : "/");
        Counter.builder("fabt.http.access_denied.count")
                .tag("role", role)
                .tag("path_prefix", path)
                .register(meterRegistry)
                .increment();
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("access_denied", "Insufficient permissions", 403));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthenticationException(AuthenticationException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(new ErrorResponse("unauthorized", "Authentication required", 401));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        // Honor @ResponseStatus annotations (e.g., AvailabilityInvariantViolation → 422).
        // Without this check, the catch-all swallows them and returns 500.
        org.springframework.web.bind.annotation.ResponseStatus responseStatus =
                ex.getClass().getAnnotation(org.springframework.web.bind.annotation.ResponseStatus.class);
        if (responseStatus != null) {
            HttpStatus status = responseStatus.value();
            return ResponseEntity
                    .status(status)
                    .body(new ErrorResponse(status.name().toLowerCase(), ex.getMessage(), status.value()));
        }

        log.error("Unhandled exception", ex);
        Counter.builder("fabt.error.unhandled.count")
                .tag("exception_class", ex.getClass().getSimpleName())
                .register(meterRegistry)
                .increment();
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("internal_error", "An unexpected error occurred", 500));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Bad request (IllegalArgumentException): {}", ex.getMessage(), ex);
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage("error.bad_request", null, ex.getMessage(), locale);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse("bad_request", message, 400));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.warn("Conflict (IllegalStateException): {}", ex.getMessage());
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage("error.conflict", null, ex.getMessage(), locale);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", message, 409));
    }

    /**
     * Phase A3 D21 — cross-tenant ciphertext rejection. The decrypt path
     * detected that the kid in a v1 envelope resolves to a different
     * tenant than the caller requested. Mapped to 403 with the D3
     * envelope; an audit_events row tagged
     * {@code CROSS_TENANT_CIPHERTEXT_REJECTED} is published with the kid
     * + expected/actual tenants + actor + IP per warroom Q5 (audit
     * publish wired in a follow-up commit when the ApplicationEventPublisher
     * + ServletRequest accessors are confirmed; for now this handler
     * counters the metric and returns 403 cleanly).
     */
    @ExceptionHandler(org.fabt.shared.security.CrossTenantCiphertextException.class)
    public ResponseEntity<ErrorResponse> handleCrossTenantCiphertext(
            org.fabt.shared.security.CrossTenantCiphertextException ex) {
        log.warn("Cross-tenant ciphertext rejected: kid={} expected={} actual={}",
                ex.getKid(), ex.getExpectedTenantId(), ex.getActualTenantId());

        Counter.builder("fabt.security.cross_tenant_ciphertext_rejected.count")
                .tag("expected_tenant", ex.getExpectedTenantId().toString())
                .register(meterRegistry)
                .increment();

        // audit_events publish per warroom Q5 — JSONB shape:
        // {kid, expectedTenantId, actualTenantId, actorUserId, sourceIp}
        java.util.UUID actorUserId = currentActorUserId();
        String sourceIp = currentSourceIp();
        java.util.Map<String, Object> details = java.util.Map.of(
                "kid", ex.getKid().toString(),
                "expectedTenantId", ex.getExpectedTenantId().toString(),
                "actualTenantId", ex.getActualTenantId().toString(),
                "actorUserId", actorUserId == null ? "null" : actorUserId.toString(),
                "sourceIp", sourceIp == null ? "null" : sourceIp);
        eventPublisher.publishEvent(new org.fabt.shared.audit.AuditEventRecord(
                actorUserId,
                null, // no targetUserId — the target is a ciphertext, not a user
                "CROSS_TENANT_CIPHERTEXT_REJECTED",
                details,
                sourceIp));

        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(new ErrorResponse("cross_tenant",
                        "Ciphertext does not belong to the requested tenant.", 403));
    }

    private static java.util.UUID currentActorUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) return null;
        try {
            return java.util.UUID.fromString(auth.getName());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    private static String currentSourceIp() {
        try {
            var attrs = org.springframework.web.context.request.RequestContextHolder.currentRequestAttributes();
            if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra) {
                return sra.getRequest().getRemoteAddr();
            }
        } catch (IllegalStateException noRequestBound) {
            // happens when called outside a request context (e.g., from a scheduled task)
        }
        return null;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        log.warn("Not found (NoSuchElementException): {}", ex.getMessage());
        // D9 (cross-tenant-isolation-audit Phase 4.4): emit counter on
        // EVERY tenant-owned resource 404, intentionally not distinguishing
        // cross-tenant probes from legitimate "not found" — both look
        // identical by design (D3: 404-not-403).
        Counter.builder("fabt.security.cross_tenant_404s")
                .tag("resource_type", extractResourceType(ex.getMessage()))
                .register(meterRegistry)
                .increment();
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage("error.not_found", null, ex.getMessage(), locale);
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", message, 404));
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateKey(DuplicateKeyException ex) {
        log.warn("Duplicate key: {}", ex.getMessage());
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage("error.duplicate_entry", null,
                "A record with this identifier already exists.", locale);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("duplicate_entry", message, 409));
    }

    @ExceptionHandler(org.springframework.dao.DataAccessException.class)
    public ResponseEntity<ErrorResponse> handleDataAccess(org.springframework.dao.DataAccessException ex) {
        log.warn("Data access failure (retries exhausted): {}", ex.getMessage());
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage("error.conflict", null,
                "The operation could not be completed due to a temporary conflict. Please try again.", locale);
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("conflict", message, 409));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        log.warn("Validation failed: {}", ex.getMessage());
        Locale locale = LocaleContextHolder.getLocale();
        String message = messageSource.getMessage("error.validation_failed", null,
                "Request validation failed.", locale);

        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> Map.of(
                        "field", error.getField(),
                        "rejected_value", String.valueOf(error.getRejectedValue()),
                        "reason", error.getDefaultMessage() != null ? error.getDefaultMessage() : "invalid"
                ))
                .toList();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(
                        "validation_failed",
                        message,
                        400,
                        Map.of("field_errors", fieldErrors)
                ));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSize(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("File upload too large: {}", ex.getMessage());
        return ResponseEntity
                .status(413)
                .body(new ErrorResponse("file_too_large",
                        "File exceeds the maximum upload size of 10MB.", 413));
    }

    private static String extractFirstPathSegment(String path) {
        if (path == null || path.isBlank()) return "/";
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return "/" + (slash > 0 ? trimmed.substring(0, slash) : trimmed);
    }

    /**
     * Extract a lowercase resource type from a NoSuchElementException
     * message like "Shelter not found: 123..." → "shelter". Falls back
     * to "unknown" for unrecognized formats.
     */
    private static String extractResourceType(String message) {
        if (message == null) return "unknown";
        int idx = message.toLowerCase(java.util.Locale.ROOT).indexOf(" not found");
        if (idx <= 0) return "unknown";
        return message.substring(0, idx).toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z_]", "_").trim();
    }
}
