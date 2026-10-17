package com.agrosense.backend.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/** A request answered with this status and this message, which is written for whoever sent it. */
@Getter
public class ApiException extends RuntimeException {

    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
