package com.synctool.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import lombok.extern.slf4j.Slf4j;

/**
 * Turns service-layer exceptions into JSON responses for the REST endpoints.
 *
 * <p>Messages from the service layer are i18n keys (e.g. {@code error.project.not.found}); the
 * key is returned as-is so the browser can localize it, with the raw text as a fallback for
 * anything unkeyed.
 */
@RestControllerAdvice(basePackages = "com.synctool.controller.api")
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> onBadRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(false, e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> onConflict(IllegalStateException e) {
        // A state problem is the user's to resolve (stop the project, fix the connection),
        // so 409 rather than 500.
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body(false, e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> onUnexpected(Exception e) {
        log.error("Unhandled API error", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(false, e.getMessage() == null ? e.toString() : e.getMessage()));
    }

    private Map<String, Object> body(boolean success, String message) {
        return Map.of("success", success, "message", String.valueOf(message));
    }
}
