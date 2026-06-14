package com.metabion.controller.api;

import com.metabion.exception.InvalidTokenException;
import com.metabion.exception.StaffInvitationException;
import com.metabion.exception.ValidationException;
import com.metabion.service.RateLimitedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.MailException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RestControllerAdvice(basePackages = "com.metabion.controller.api")
public class GlobalExceptionHandler {

    private static final Map<String, String> INVALID_CREDENTIALS = Map.of("error", "invalid_credentials");
    private static final Map<String, String> INVALID_TOKEN = Map.of("error", "invalid_token");
    private static final Map<String, String> OK = Map.of("status", "ok");

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, String>> auth(AuthenticationException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<Map<String, String>> invalidToken(InvalidTokenException e) {
        return ResponseEntity.badRequest().body(INVALID_TOKEN);
    }

    @ExceptionHandler(StaffInvitationException.class)
    public ResponseEntity<Map<String, String>> staffInvitation(StaffInvitationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException e) {
        var fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        err -> Optional.ofNullable(err.getDefaultMessage()).orElse("invalid"),
                        (a, b) -> a));
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_failed",
                "fields", fields));
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, String>> validation(ValidationException e) {
        return ResponseEntity.badRequest().body(Map.of("error", "validation_failed"));
    }

    @ExceptionHandler(MailException.class)
    public ResponseEntity<Map<String, String>> mailUnavailable(MailException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "mail_unavailable"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> responseStatus(ResponseStatusException e) {
        var statusCode = e.getStatusCode();
        var error = "request_failed";
        if (statusCode.isSameCodeAs(HttpStatus.FORBIDDEN)) {
            error = "forbidden";
        } else if (statusCode.isSameCodeAs(HttpStatus.NOT_FOUND)) {
            error = "not_found";
        } else if (statusCode.isSameCodeAs(HttpStatus.UNAUTHORIZED)) {
            error = "unauthorized";
        }
        return ResponseEntity.status(statusCode).body(Map.of("error", error));
    }

    @ExceptionHandler(RateLimitedException.class)
    public ResponseEntity<Map<String, String>> rateLimited(RateLimitedException e) {
        if ("login".equals(e.endpoint())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(INVALID_CREDENTIALS);
        }
        return ResponseEntity.ok(OK);
    }
}
