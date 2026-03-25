package org.fabt.shared.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final MessageSource messageSource;

    public GlobalExceptionHandler(MessageSource messageSource) {
        this.messageSource = messageSource;
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
}
