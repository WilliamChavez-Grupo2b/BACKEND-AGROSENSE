package com.agrosense.backend.pattern.structural.adapter.esp32;

import com.agrosense.backend.pattern.structural.bridge.ActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.IrrigationCommand;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.service.DeviceService;
import lombok.extern.slf4j.Slf4j;

import java.util.Objects;

/**
 * Decorator of the actuator while the ESP32 is read over TCP: that ESP32 takes no orders (it never reads what its
 * client sends), so an irrigation for its crop is only recorded, as with IRRIGATION_ACTUATOR=simulated, and nothing is
 * ever written to the socket. Orders for other crops go to the actuator as before.
 *
 * <p>TODO(esp32-tcp): once the ESP32 has an order protocol (and its pump and relay), send the order there instead.
 */
@Slf4j
public class Esp32ReadOnlyActuatorDriver implements ActuatorDriver {

    private final ActuatorDriver inner;
    private final DeviceRepository devices;
    private final String deviceId;

    public Esp32ReadOnlyActuatorDriver(ActuatorDriver inner, DeviceRepository devices, String deviceId) {
        this.inner = inner;
        this.devices = devices;
        this.deviceId = deviceId;
    }

    @Override
    public void start(IrrigationCommand command) {
        boolean readOnly = devices.findByDeviceCode(DeviceService.normalize(deviceId))
                .map(device -> Objects.equals(device.getCrop().getIdCrop(), command.cropId()))
                .orElse(false);
        if (readOnly) {
            log.info("ESP32 {} takes no orders over TCP: the irrigation of crop {} ({} min) is recorded only",
                    deviceId, command.cropId(), command.minutes());
            return;
        }
        inner.start(command);
    }

    @Override
    public int maxRunMinutes() {
        return inner.maxRunMinutes();
    }
}
