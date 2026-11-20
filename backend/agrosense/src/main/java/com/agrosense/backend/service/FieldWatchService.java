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
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

    static final long SILENT_DEVICES_MS = 300_000;
    static final long FROST_MS = 3_600_000;
    static final long IRRIGATION_RESULTS_MS = 600_000;

    /** When a watch runs next: the scheduler waits its delay after start-up, then the same delay after each run. */
    public record NextRun(String command, Instant at) {
    }

    private final Clock clock = Clock.systemUTC();
    private final Instant startedAt = clock.instant();
    private final FieldWatchInvoker invoker = new FieldWatchInvoker(clock);
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

    @Scheduled(initialDelay = SILENT_DEVICES_MS, fixedDelay = SILENT_DEVICES_MS)
    public void watchSilentDevices() {
        invoker.run(silentDevices);
    }

    @Scheduled(initialDelay = FROST_MS, fixedDelay = FROST_MS)
    public void watchFrost() {
        invoker.run(frost);
    }

    @Scheduled(initialDelay = IRRIGATION_RESULTS_MS, fixedDelay = IRRIGATION_RESULTS_MS)
    public void recordIrrigationResults() {
        invoker.run(irrigationResults);
    }

    /** The last watches run, newest first. */
    public List<FieldWatchInvoker.Run> history() {
        return invoker.history();
    }

    /** When each watch runs next, soonest first. */
    public List<NextRun> nextRuns() {
        return nextRuns(startedAt, history());
    }

    static List<NextRun> nextRuns(Instant startedAt, List<FieldWatchInvoker.Run> history) {
        return Stream.of(
                        next("silent devices", SILENT_DEVICES_MS, startedAt, history),
                        next("frost", FROST_MS, startedAt, history),
                        next("irrigation results", IRRIGATION_RESULTS_MS, startedAt, history))
                .sorted(Comparator.comparing(NextRun::at))
                .toList();
    }

    private static NextRun next(String command, long delayMs, Instant startedAt, List<FieldWatchInvoker.Run> history) {
        Instant from = history.stream().filter(run -> run.command().equals(command)).findFirst()
                .map(run -> run.startedAt().plus(run.took()))
                .orElse(startedAt);
        return new NextRun(command, from.plusMillis(delayMs));
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
