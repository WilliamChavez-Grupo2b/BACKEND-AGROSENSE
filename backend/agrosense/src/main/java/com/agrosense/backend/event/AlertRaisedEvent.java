package com.agrosense.backend.event;

import com.agrosense.backend.dto.response.AlertResponse;

public record AlertRaisedEvent(String ownerEmail, AlertResponse alert) {
}
