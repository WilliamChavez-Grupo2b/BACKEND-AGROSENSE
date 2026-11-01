package com.agrosense.backend.service;

import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;
import com.agrosense.backend.pattern.creational.factory.alert.FrostRiskAlertCreator;
import com.agrosense.backend.pattern.creational.factory.alert.SensorOfflineAlertCreator;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The alerts no reading can raise: a device that stopped reporting and a frost that has not arrived yet. */
@Slf4j
@Service
public class FieldWatchService {

    private final DeviceRepository deviceRepository;
    private final CropRepository cropRepository;
    private final SensorFacade sensorFacade;
    private final WeatherProvider weather;
    private final int silentMinutes;
    private final boolean frostWatch;
    private final SensorOfflineAlertCreator offline = new SensorOfflineAlertCreator();
    private final FrostRiskAlertCreator frost = new FrostRiskAlertCreator();

    public FieldWatchService(DeviceRepository deviceRepository, CropRepository cropRepository,
            SensorFacade sensorFacade, WeatherProvider weather,
            @Value("${alerts.device-silent-minutes:15}") int silentMinutes,
            @Value("${alerts.frost-watch:true}") boolean frostWatch) {
        this.deviceRepository = deviceRepository;
        this.cropRepository = cropRepository;
        this.sensorFacade = sensorFacade;
        this.weather = weather;
        this.silentMinutes = silentMinutes;
        this.frostWatch = frostWatch;
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    public void watchSilentDevices() {
        if (silentMinutes <= 0) {
            return;
        }
        LocalDateTime limit = LocalDateTime.now().minusMinutes(silentMinutes);
        for (Device device : deviceRepository.findByActiveTrueAndCropActiveTrueAndLastEventAtBefore(limit)) {
            sensorFacade.raise(offline, AlertContext.ofSilentDevice(device.getCrop(), device.getDeviceCode(),
                    device.getLastEventAt()));
        }
    }

    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    public void watchFrost() {
        if (!frostWatch) {
            return;
        }
        Map<Estate, List<Crop>> byEstate = cropRepository.findByActiveTrueAndEstateLatitudeIsNotNull().stream()
                .collect(Collectors.groupingBy(Crop::getEstate));
        byEstate.forEach((estate, crops) -> weather.coldestDayAhead(estate.getLatitude(), estate.getLongitude())
                .ifPresent(day -> crops.forEach(crop -> sensorFacade.raise(frost,
                        AlertContext.ofForecast(crop, day.minimumTemperature(), day.date())))));
    }
}
