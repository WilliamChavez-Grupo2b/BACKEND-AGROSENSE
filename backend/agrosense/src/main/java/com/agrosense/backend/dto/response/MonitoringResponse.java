package com.agrosense.backend.dto.response;

import com.agrosense.backend.pattern.behavioral.command.FieldWatchInvoker;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReceiverStatus;

import java.time.Instant;
import java.util.List;

/**
 * What GET /api/devices/monitoring shows on the frontend's «Monitoreo» page: the ESP32 receiver (its State and the
 * Strategy that averages it), the watering rule read by the Interpreter, or null when it is off, and the last
 * watches run by the Command invoker, newest first.
 */
public record MonitoringResponse(Esp32ReceiverStatus esp32, String wateringRule, List<WatchRun> watches) {

    public record WatchRun(String command, Instant startedAt, long tookMillis) {

        public static WatchRun of(FieldWatchInvoker.Run run) {
            return new WatchRun(run.command(), run.startedAt(), run.took().toMillis());
        }
    }
}
