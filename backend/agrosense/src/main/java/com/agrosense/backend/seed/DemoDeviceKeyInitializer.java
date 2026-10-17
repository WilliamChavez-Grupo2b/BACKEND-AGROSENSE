package com.agrosense.backend.seed;

import com.agrosense.backend.service.DeviceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDeviceKeyInitializer implements ApplicationRunner {

    private final DeviceService deviceService;

    @Value("${agrosense.seed.device-code:RPI-DEMO-01}")
    private String deviceCode;

    @Value("${agrosense.seed.device-key:}")
    private String deviceKey;

    @Override
    public void run(ApplicationArguments args) {
        if (deviceKey.isBlank()) {
            return;
        }
        if (deviceService.setKey(deviceCode, deviceKey)) {
            log.info("Key set for demo device {}", deviceCode);
        } else {
            log.warn("Demo device {} not found: was database/seed_demo.sql applied?", deviceCode);
        }
    }
}
