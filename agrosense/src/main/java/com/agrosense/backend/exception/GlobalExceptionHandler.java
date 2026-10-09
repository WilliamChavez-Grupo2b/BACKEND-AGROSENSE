package com.agrosense.backend.exception;

import com.agrosense.backend.dto.response.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler({ResourceNotFoundException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> handleNotFound() {
        return respond(HttpStatus.NOT_FOUND, "No se encontró el recurso solicitado.");
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<ErrorResponse> handleBusinessRule(BusinessRuleException exception) {
        return respond(HttpStatus.CONFLICT, exception.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .findFirst()
                .orElse("Los datos enviados no son válidos.");
        return respond(HttpStatus.BAD_REQUEST, message);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable() {
        return respond(HttpStatus.BAD_REQUEST, "Los datos enviados no son válidos.");
    }

    /** Wrong e-mail, wrong password and disabled accounts all get the same answer on purpose. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication() {
        return respond(HttpStatus.UNAUTHORIZED, "Credenciales incorrectas.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied() {
        return respond(HttpStatus.FORBIDDEN, "No tienes permiso para realizar esta acción.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
        // Spring MVC's own client errors (404, 405, 415...) carry their status code.
        if (exception instanceof org.springframework.web.ErrorResponse response
                && response.getStatusCode().is4xxClientError()) {
            return respond(HttpStatus.valueOf(response.getStatusCode().value()), "La solicitud no es válida.");
        }
        log.error("Error inesperado al atender la solicitud", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado. Inténtalo de nuevo.");
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}
