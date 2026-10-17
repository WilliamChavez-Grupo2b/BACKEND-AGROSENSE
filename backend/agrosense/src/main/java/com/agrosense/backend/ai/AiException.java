package com.agrosense.backend.ai;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class AiException extends RuntimeException {

    private final HttpStatus status;

    public AiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
}
