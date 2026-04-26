package org.fabt.tenant.api;

import java.util.Locale;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.fabt.shared.web.ErrorResponse;
import org.fabt.tenant.domain.TenantStateGuardException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Tenant-module-local exception handler. Lives in the tenant module because the
 * {@link TenantStateGuardException} type resides here, and
 * {@code org.fabt.shared.web.GlobalExceptionHandler} must not depend on any domain
 * module (enforced by {@code ArchitectureTest.shared_non_security_should_not_depend_on_modules}).
 * Spring supports multiple {@code @RestControllerAdvice} beans — this one handles
 * tenant-lifecycle exceptions, the shared one handles cross-cutting concerns
 * (auth, validation, etc.).
 *
 * <p><b>Precedence (G-4.6 warroom fix):</b> {@code @Order(HIGHEST_PRECEDENCE)} is
 * required because {@code GlobalExceptionHandler.handleUnexpected(Exception)} is a
 * catch-all that matches every {@code Throwable}. Spring's
 * {@code ExceptionHandlerExceptionResolver} iterates {@code @ControllerAdvice}
 * beans in {@code @Order} order, returning the first match — without the explicit
 * order, the unannotated Global bean often wins by classpath / bean-name fallback,
 * silently mapping {@link org.fabt.tenant.domain.IllegalStateTransitionException}
 * to a 500 instead of the 409 we hand-wired below. The HIGHEST_PRECEDENCE marker
 * pins module-local advice ahead of the shared catch-all without changing
 * Global's behavior for every other handler. (See diagnosis behind G-4.6
 * controller IT 5/5 failures: SUSPENDED→SUSPENDED self-transition surfaced as
 * 500 even with this handler defined, because Global was queried first.)</p>
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantLifecycleExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(TenantLifecycleExceptionAdvice.class);

    private final MessageSource messageSource;
    private final MeterRegistry meterRegistry;

    public TenantLifecycleExceptionAdvice(MessageSource messageSource, MeterRegistry meterRegistry) {
        this.messageSource = messageSource;
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(TenantStateGuardException.class)
    public ResponseEntity<ErrorResponse> handleTenantStateGuard(TenantStateGuardException ex) {
        // Phase F §D3 — no existence leak. Branch on HTTP method so reads get 404
        // (attacker probing UUIDs sees the same 404 whether the tenant exists or not)
        // and writes get 503 with Retry-After (operator action required before the
        // tenant will accept mutations again — suspends can be lifted, archives cannot).
        String httpMethod = currentRequestMethod();
        boolean isReadMethod = "GET".equals(httpMethod) || "HEAD".equals(httpMethod);

        // Counter for observability — tenant-id intentionally NOT tagged (would
        // leak a non-existent tenant id guess via Prometheus cardinality).
        Counter.builder("fabt.security.tenant_state_guard_rejection")
                .tag("kind", ex.kind().name().toLowerCase())
                .tag("http_method", httpMethod)
                .register(meterRegistry)
                .increment();

        if (isReadMethod || ex.kind() == TenantStateGuardException.Kind.NOT_FOUND) {
            log.warn("TenantStateGuard 404 (kind={}, method={})", ex.kind(), httpMethod);
            Locale locale = LocaleContextHolder.getLocale();
            String message = messageSource.getMessage(
                "error.not_found", null, "The requested resource was not found", locale);
            return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("not_found", message, 404));
        }

        // Write against a non-active tenant — 503 with Retry-After. Tuned to 3600s
        // (1 hour) matching the operator-oncall response window for quarantine-review.
        // ARCHIVED/DELETED tenants will never come back, but the client still gets the
        // generic 503 — no state leak. D3 consistency: we do NOT say "this tenant is
        // archived/deleted" in the body.
        log.warn("TenantStateGuard 503 (kind={}, method={})", ex.kind(), httpMethod);
        return ResponseEntity
            .status(HttpStatus.SERVICE_UNAVAILABLE)
            .header("Retry-After", "3600")
            .body(new ErrorResponse("service_unavailable",
                "Service temporarily unavailable for this resource", 503));
    }

    /**
     * G-4.6: state-machine rejections from {@link TenantLifecycleService}
     * (e.g., suspending an already-SUSPENDED tenant, hard-deleting from
     * OFFBOARDING without going through ARCHIVED) surface as
     * {@link org.fabt.tenant.domain.IllegalStateTransitionException}.
     * Map to 409 Conflict so callers (operator UI, scripts) can
     * distinguish FSM violations from auth failures (401/403) and
     * validation errors (400). Body shape matches the other handlers
     * here for consistency.
     *
     * <p>Note: per Decision 11 the {@code @PlatformAdminOnly} aspect
     * has already committed the PAL row before this handler runs. The
     * failed transition itself is recorded by the lifecycle service via
     * {@code DetachedAuditPersister} as {@code TENANT_*_REJECTED} —
     * operators correlate that audit row with the PAL row at the same
     * timestamp.</p>
     */
    @ExceptionHandler(org.fabt.tenant.domain.IllegalStateTransitionException.class)
    public ResponseEntity<ErrorResponse> handleIllegalStateTransition(
            org.fabt.tenant.domain.IllegalStateTransitionException ex) {
        log.warn("Tenant FSM violation (409): {}", ex.getMessage());
        Counter.builder("fabt.tenant.lifecycle.fsm_rejection")
                .register(meterRegistry)
                .increment();
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("invalid_state_transition",
                        ex.getMessage(), 409));
    }

    /**
     * Current HTTP method, best-effort. Falls back to {@code "UNKNOWN"} off the request
     * path (async, test invocations) so counters still tag something sensible.
     */
    private static String currentRequestMethod() {
        var attrs = RequestContextHolder.getRequestAttributes();
        if (attrs instanceof ServletRequestAttributes sra) {
            return sra.getRequest().getMethod();
        }
        return "UNKNOWN";
    }
}
