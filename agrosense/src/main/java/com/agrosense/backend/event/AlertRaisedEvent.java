package com.agrosense.backend.event;

import com.agrosense.backend.dto.response.AlertResponse;

/** An alert was created. {@code ownerEmail} is the only user allowed to be told about it. */
public record AlertRaisedEvent(String ownerEmail, AlertResponse alert) {
}
