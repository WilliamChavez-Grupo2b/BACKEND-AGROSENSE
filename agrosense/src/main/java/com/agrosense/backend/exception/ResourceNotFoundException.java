package com.agrosense.backend.exception;

/** The record does not exist or does not belong to the authenticated user. */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
