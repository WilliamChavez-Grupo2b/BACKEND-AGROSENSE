package com.agrosense.backend.event;

import com.agrosense.backend.dto.response.SensorReadingResponse;

/** A reading was stored. {@code ownerEmail} is the only user allowed to be told about it. */
public record SensorReadingRecordedEvent(String ownerEmail, SensorReadingResponse reading) {
}
