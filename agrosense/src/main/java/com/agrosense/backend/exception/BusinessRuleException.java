package com.agrosense.backend.exception;

/** A well-formed request that cannot be accepted; the message is shown to the user. */
public class BusinessRuleException extends RuntimeException {

    public BusinessRuleException(String message) {
        super(message);
    }
}
