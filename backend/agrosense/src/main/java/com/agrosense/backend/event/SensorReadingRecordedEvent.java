package com.agrosense.backend.event;

import com.agrosense.backend.dto.response.SensorReadingResponse;

public record SensorReadingRecordedEvent(String ownerEmail, SensorReadingResponse reading) {
}
