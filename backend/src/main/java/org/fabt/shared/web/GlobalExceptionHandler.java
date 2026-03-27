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

    public GlobalExceptionHandler(MessageSource messageSource, MeterRegistry meterRegistry) {
        this.messageSource = messageSource;
        this.meterRegistry = meterRegistry;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex) {
        String path = ex.getResourcePath();
        String firstSegment = extractFirstPathSegment(path);
        Counter.builder("fabt.http.not_found.count")
                .tag("path_prefix", firstSegment)
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

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoSuchElementException ex) {
        log.warn("Not found (NoSuchElementException): {}", ex.getMessage());
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

    private static String extractFirstPathSegment(String path) {
        if (path == null || path.isBlank()) return "/";
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        int slash = trimmed.indexOf('/');
        return "/" + (slash > 0 ? trimmed.substring(0, slash) : trimmed);
    }
}
