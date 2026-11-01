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

/**
 * What no reading can raise: a device that stopped reporting and a frost that has not arrived yet. Both last longer
 * than their alert, so one already raised is not repeated, even if someone answered it. A failure on one crop or
 * estate is logged and the round goes on.
 */
@Slf4j
@Service
public class FieldWatchService {

    private static final int FROST_REPEAT_HOURS = 24;

    private final DeviceRepository deviceRepository;
    private final CropRepository cropRepository;
    private final SensorFacade sensorFacade;
    private final WeatherProvider weather;
    private final IrrigationService irrigationService;
    private final int silentMinutes;
    private final boolean frostWatch;
    private final SensorOfflineAlertCreator offline = new SensorOfflineAlertCreator();
    private final FrostRiskAlertCreator frost = new FrostRiskAlertCreator();

    public FieldWatchService(DeviceRepository deviceRepository, CropRepository cropRepository,
            SensorFacade sensorFacade, WeatherProvider weather, IrrigationService irrigationService,
            @Value("${alerts.device-silent-minutes:15}") int silentMinutes,
            @Value("${alerts.frost-watch:true}") boolean frostWatch) {
        this.deviceRepository = deviceRepository;
        this.cropRepository = cropRepository;
        this.sensorFacade = sensorFacade;
        this.weather = weather;
        this.irrigationService = irrigationService;
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
            guarded("silent device " + device.getDeviceCode(), () -> sensorFacade.raiseOnceSince(offline,
                    AlertContext.ofSilentDevice(device.getCrop(), device.getDeviceCode(), device.getLastEventAt()),
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
        LocalDateTime since = LocalDateTime.now().minusHours(FROST_REPEAT_HOURS);
        byEstate.forEach((estate, crops) -> guarded("frost at estate " + estate.getIdEstate(), () ->
                weather.outlook(estate.getLatitude(), estate.getLongitude()).ifPresent(outlook -> crops.forEach(crop ->
                        sensorFacade.raiseOnceSince(frost, AlertContext.ofForecast(crop, outlook.minimumTemperature(),
                                outlook.coldestDay()), since)))));
    }

    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    public void recordIrrigationResults() {
        guarded("irrigation results", irrigationService::recordResults);
    }

    private static void guarded(String what, Runnable work) {
        try {
            work.run();
        } catch (RuntimeException failure) {
            log.warn("Field watch failed on {}: {}", what, failure.getMessage());
        }
    }
}
