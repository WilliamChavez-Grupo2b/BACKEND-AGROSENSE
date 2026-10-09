package com.agrosense.backend.dto.response;

/** Body of every error answer; the message is written for the end user. */
public record ErrorResponse(String message) {
}
