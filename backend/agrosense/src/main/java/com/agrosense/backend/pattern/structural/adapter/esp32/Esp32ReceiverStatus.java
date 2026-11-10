package com.agrosense.backend.pattern.structural.adapter.esp32;

import java.time.OffsetDateTime;

/**
 * The state of the TCP receiver for GET /api/devices/esp32/status. It names the device, never its address, and holds
 * no key: there is none.
 */
public record Esp32ReceiverStatus(
        boolean enabled,
        boolean connected,
        String deviceId,
        int aggregateSeconds,
        OffsetDateTime lastBlockAt,
        OffsetDateTime lastStoredAt,
        long storedReadings,
        long refusedReadings,
        long discardedBlocks,
        long skippedLines,
        int connections) {
}
