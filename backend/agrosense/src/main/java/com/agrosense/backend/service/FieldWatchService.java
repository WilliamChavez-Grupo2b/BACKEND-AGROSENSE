package com.agrosense.backend.service;

import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.pattern.behavioral.command.FieldWatchCommand;
import com.agrosense.backend.pattern.behavioral.command.FieldWatchInvoker;
import com.agrosense.backend.pattern.behavioral.templatemethod.ScheduledWatch;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;
import com.agrosense.backend.pattern.creational.factory.alert.FrostRiskAlertCreator;
import com.agrosense.backend.pattern.creational.factory.alert.SensorOfflineAlertCreator;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * What no reading can raise: a device that stopped reporting and a frost that has not arrived yet. Both last longer
 * than their alert, so one already raised is not repeated, even if someone answered it. A failure on one crop or
 * estate is logged and the round goes on.
 *
 * <p>Each watch is a Command (FieldWatchCommand) run by a FieldWatchInvoker, which keeps the last runs; the
 * scheduler only says when. All three follow the same Template Method (ScheduledWatch): skip it when off, run it,
 * log a failure.
 */
@Service
public class FieldWatchService {

    private static final int FROST_REPEAT_HOURS = 24;

    private final FieldWatchInvoker invoker = new FieldWatchInvoker(Clock.systemUTC());
    private final FieldWatchCommand silentDevices;
    private final FieldWatchCommand frost;
    private final FieldWatchCommand irrigationResults;

    public FieldWatchService(DeviceRepository deviceRepository, CropRepository cropRepository,
            SensorFacade sensorFacade, WeatherProvider weather, IrrigationService irrigationService,
            @Value("${alerts.device-silent-minutes:15}") int silentMinutes,
            @Value("${alerts.frost-watch:true}") boolean frostWatch) {
        this.silentDevices = new SilentDevicesWatch(deviceRepository, sensorFacade, silentMinutes);
        this.frost = new FrostWatch(cropRepository, sensorFacade, weather, frostWatch);
        this.irrigationResults = new IrrigationResultsWatch(irrigationService);
    }

    @Scheduled(initialDelay = 300_000, fixedDelay = 300_000)
    public void watchSilentDevices() {
        invoker.run(silentDevices);
    }

    @Scheduled(initialDelay = 3_600_000, fixedDelay = 3_600_000)
    public void watchFrost() {
        invoker.run(frost);
    }

    @Scheduled(initialDelay = 600_000, fixedDelay = 600_000)
    public void recordIrrigationResults() {
        invoker.run(irrigationResults);
    }

    /** The last watches run, newest first. */
    public List<FieldWatchInvoker.Run> history() {
        return invoker.history();
    }

    /** A device that has not reported for ALERTS_DEVICE_SILENT_MINUTES gets a SENSOR_OFFLINE alert. */
    static final class SilentDevicesWatch extends ScheduledWatch {

        private final DeviceRepository deviceRepository;
        private final SensorFacade sensorFacade;
        private final int silentMinutes;
        private final SensorOfflineAlertCreator offline = new SensorOfflineAlertCreator();

        SilentDevicesWatch(DeviceRepository deviceRepository, SensorFacade sensorFacade, int silentMinutes) {
            this.deviceRepository = deviceRepository;
            this.sensorFacade = sensorFacade;
            this.silentMinutes = silentMinutes;
        }

        @Override
        public String name() {
            return "silent devices";
        }

        @Override
        protected boolean isEnabled() {
            return silentMinutes > 0;
        }

        @Override
        protected void watch() {
            LocalDateTime limit = LocalDateTime.now().minusMinutes(silentMinutes);
            for (Device device : deviceRepository.findByActiveTrueAndCropActiveTrueAndLastEventAtBefore(limit)) {
                guarded("silent device " + device.getDeviceCode(), () -> sensorFacade.raiseOnceSince(offline,
                        AlertContext.ofSilentDevice(device.getCrop(), device.getDeviceCode(), device.getLastEventAt()),
                        device.getLastEventAt()));
            }
        }
    }

    /** A frost forecast for an estate warns each of its active crops once a day. */
    static final class FrostWatch extends ScheduledWatch {

        private final CropRepository cropRepository;
        private final SensorFacade sensorFacade;
        private final WeatherProvider weather;
        private final boolean enabled;
        private final FrostRiskAlertCreator frost = new FrostRiskAlertCreator();

        FrostWatch(CropRepository cropRepository, SensorFacade sensorFacade, WeatherProvider weather,
                boolean enabled) {
            this.cropRepository = cropRepository;
            this.sensorFacade = sensorFacade;
            this.weather = weather;
            this.enabled = enabled;
        }

        @Override
        public String name() {
            return "frost";
        }

        @Override
        protected boolean isEnabled() {
            return enabled;
        }

        @Override
        protected void watch() {
            Map<Estate, List<Crop>> byEstate = cropRepository.findByActiveTrueAndEstateLatitudeIsNotNull().stream()
                    .collect(Collectors.groupingBy(Crop::getEstate));
            LocalDateTime since = LocalDateTime.now().minusHours(FROST_REPEAT_HOURS);
            byEstate.forEach((estate, crops) -> guarded("frost at estate " + estate.getIdEstate(), () ->
                    weather.outlook(estate.getLatitude(), estate.getLongitude()).ifPresent(outlook -> crops.forEach(
                            crop -> sensorFacade.raiseOnceSince(frost, AlertContext.ofForecast(crop,
                                    outlook.minimumTemperature(), outlook.coldestDay()), since)))));
        }
    }

    /** Irrigations that ended get the moisture they left recorded. */
    static final class IrrigationResultsWatch extends ScheduledWatch {

        private final IrrigationService irrigationService;

        IrrigationResultsWatch(IrrigationService irrigationService) {
            this.irrigationService = irrigationService;
        }

        @Override
        public String name() {
            return "irrigation results";
        }

        @Override
        protected void watch() {
            irrigationService.recordResults();
        }
    }
}
