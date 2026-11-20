package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.ReadingSource;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.behavioral.templatemethod.ScheduledWatch;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.Random;
import java.util.Set;

/**
 * The virtual sensor: where no ESP32 can be reached (Render cannot see 192.168.4.1), every active sensor that has had
 * no real reading for {@code simulator.quiet-minutes} gets a plausible one each {@code simulator.interval-ms}, marked
 * SIMULATED. It goes through SensorFacade like any other reading, so it is calibrated, validated, can raise alerts
 * and is pushed over STOMP (/user/queue/sensores). A real reading (any source but SIMULATED or SEED) in that window
 * stops it for that sensor: it never writes over live data.
 *
 * <p>Off unless SIMULATOR_ENABLED=true. A Template Method like the other field watches (ScheduledWatch): skip it
 * when off, run it, and keep one failing sensor from stopping the round.
 */
@Component
public class VirtualSensorSimulator extends ScheduledWatch {

    /** Sources that do not count as "real": the simulator's own readings and the sample rows. */
    static final Set<ReadingSource> NOT_REAL = EnumSet.of(ReadingSource.SIMULATED, ReadingSource.SEED);

    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final SensorFacade sensorFacade;
    private final boolean enabled;
    private final Duration quiet;
    private final Clock clock;
    private final Random random;

    @Autowired
    public VirtualSensorSimulator(SensorRepository sensorRepository, SensorReadingRepository readingRepository,
            SensorFacade sensorFacade, @Value("${simulator.enabled:false}") boolean enabled,
            @Value("${simulator.quiet-minutes:10}") int quietMinutes) {
        this(sensorRepository, readingRepository, sensorFacade, enabled, Duration.ofMinutes(quietMinutes),
                Clock.systemDefaultZone(), new Random());
    }

    public VirtualSensorSimulator(SensorRepository sensorRepository, SensorReadingRepository readingRepository,
            SensorFacade sensorFacade, boolean enabled, Duration quiet, Clock clock, Random random) {
        this.sensorRepository = sensorRepository;
        this.readingRepository = readingRepository;
        this.sensorFacade = sensorFacade;
        this.enabled = enabled;
        this.quiet = quiet;
        this.clock = clock;
        this.random = random;
    }

    @Scheduled(initialDelayString = "${simulator.interval-ms:60000}", fixedDelayString = "${simulator.interval-ms:60000}")
    public void tick() {
        execute();
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public String name() {
        return "virtual sensors";
    }

    @Override
    protected boolean isEnabled() {
        return enabled;
    }

    @Override
    protected void watch() {
        LocalDateTime now = LocalDateTime.now(clock);
        for (Sensor sensor : sensorRepository.findByActiveTrueAndCropActiveTrueAndCropEstateUserActiveTrue()) {
            guarded("virtual sensor " + sensor.getSensorCode(), () -> simulate(sensor, now));
        }
    }

    /** One simulated reading for {@code sensor}, unless a real one arrived within the quiet window. */
    public boolean simulate(Sensor sensor, LocalDateTime now) {
        if (readingRepository.existsBySensorIdSensorAndSourceNotInAndRecordedAtAfter(sensor.getIdSensor(), NOT_REAL,
                now.minus(quiet))) {
            return false;
        }
        Double last = readingRepository
                .findFirstBySensorIdSensorAndQualityOrderByRecordedAtDesc(sensor.getIdSensor(),
                        ValidationDecorator.QUALITY_OK)
                .map(SensorReading::getValue).map(BigDecimal::doubleValue).orElse(null);
        double value = next(sensor.getSensorType(), last, now);
        sensorFacade.record(SensorReadingData.builder()
                .sensorCode(sensor.getSensorCode())
                .value(BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP))
                .recordedAt(now)
                .source(ReadingSource.SIMULATED)
                .build());
        return true;
    }

    /**
     * A bounded random walk around the last value. Temperatures, light and the air's humidity follow the day instead:
     * they are pulled towards a daily curve (warmest at 15:00) with a little noise.
     */
    double next(SensorType type, Double last, LocalDateTime now) {
        Profile profile = Profile.of(type);
        double hour = now.getHour() + now.getMinute() / 60.0;
        double daily = Math.sin(2 * Math.PI * (hour - 9) / 24); // 1 at 15:00, -1 at 03:00
        double start = last == null ? profile.typical + profile.daily * daily : last;
        double value;
        if (profile.daily != 0) {
            double target = profile.typical + profile.daily * daily;
            value = start + (target - start) * 0.3 + random.nextGaussian() * profile.step;
        } else if (type == SensorType.RAIN_GAUGE) {
            value = random.nextDouble() < 0.05 ? random.nextDouble() * 3 : 0;
        } else {
            value = start + random.nextGaussian() * profile.step + (profile.typical - start) * 0.02;
        }
        return Math.max(profile.min, Math.min(profile.max, value));
    }

    private record Profile(double min, double max, double typical, double daily, double step) {

        static Profile of(SensorType type) {
            return switch (type) {
                case SOIL_MOISTURE -> new Profile(15, 85, 50, 0, 0.8);
                case AIR_TEMPERATURE -> new Profile(-5, 40, 19, 6, 0.3);
                case SOIL_TEMPERATURE -> new Profile(0, 35, 17, 2.5, 0.15);
                case RELATIVE_HUMIDITY -> new Profile(25, 98, 72, -15, 1.0);
                case PH -> new Profile(5.0, 8.0, 6.3, 0, 0.03);
                case CONDUCTIVITY -> new Profile(0.3, 3.0, 1.4, 0, 0.03);
                case LIGHT -> new Profile(0, 90_000, 30_000, 45_000, 1_500);
                case RAIN_GAUGE -> new Profile(0, 20, 0, 0, 0);
            };
        }
    }
}
