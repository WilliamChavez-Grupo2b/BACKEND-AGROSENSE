package com.agrosense.backend.seed;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.CropStage;
import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.models.AiPrediction;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.AiPredictionRepository;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Inserts sample data for local development. It only runs with the "seed" profile, needs
 * SEED_USER_PASSWORD to be set, and does nothing when the seed user already exists.
 */
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private static final int READING_HOURS = 24;

    private final UserRepository userRepository;
    private final EstateRepository estateRepository;
    private final CropRepository cropRepository;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final IrrigationRepository irrigationRepository;
    private final AiPredictionRepository predictionRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${agrosense.seed.email:demo@agrosense.co}")
    private String seedEmail;

    @Value("${agrosense.seed.password:}")
    private String seedPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(seedEmail)) {
            log.info("Seed user {} already exists, skipping sample data", seedEmail);
            return;
        }
        if (seedPassword.isBlank()) {
            throw new IllegalStateException("The 'seed' profile requires SEED_USER_PASSWORD to be set");
        }
        LocalDateTime now = LocalDateTime.now();
        User user = seedUser();

        Estate esperanza = seedEstate(user, "Finca La Esperanza", "Pasto, Nariño", "1.2136000", "-77.2811000", "12.50");
        Estate mirador = seedEstate(user, "Finca El Mirador", "La Unión, Nariño", "1.6044000", "-77.1317000", "6.00");

        Crop coffee = seedCrop(esperanza, "Café", "Castillo", 18, CropStage.FLOWERING);
        Crop plantain = seedCrop(esperanza, "Plátano", "Hartón", 10, CropStage.GROWTH);
        Crop potato = seedCrop(mirador, "Papa", "Pastusa", 2, CropStage.GERMINATION);

        Sensor coffeeMoisture = seedSensor(coffee, "AS-001", SensorType.SOIL_MOISTURE, "Lote norte", "%", 52, 14, now);
        Sensor coffeeTemperature = seedSensor(coffee, "AS-002", SensorType.AIR_TEMPERATURE, "Lote norte", "°C", 21, 6, now);
        seedSensor(coffee, "AS-003", SensorType.PH, "Lote sur", "pH", 6.2, 0.3, now);
        Sensor plantainMoisture = seedSensor(plantain, "AS-004", SensorType.SOIL_MOISTURE, "Platanera", "%", 66, 9, now);
        seedSensor(plantain, "AS-005", SensorType.CONDUCTIVITY, "Platanera", "dS/m", 1.4, 0.2, now);
        sensorRepository.save(Sensor.builder()
                .crop(potato)
                .sensorCode("AS-006")
                .sensorType(SensorType.LIGHT)
                .location("Parcela 1")
                .active(false)
                .lastReadingAt(now.minusDays(3))
                .build());

        seedAlert(coffee, coffeeMoisture, AlertType.LOW_HUMIDITY, Severity.HIGH,
                "La humedad del suelo está por debajo del mínimo configurado.", "37.4", now.minusHours(2), false);
        seedAlert(plantain, plantainMoisture, AlertType.RECOMMENDED_WATERING, Severity.MEDIUM,
                "Se recomienda regar en las próximas horas.", "58.1", now.minusHours(5), false);
        seedAlert(potato, null, AlertType.PEST_DETECTED, Severity.VERY_HIGH,
                "Posible presencia de plaga reportada en la parcela.", null, now.minusHours(9), false);
        seedAlert(coffee, coffeeTemperature, AlertType.HIGH_TEMPERATURE, Severity.LOW,
                "La temperatura superó brevemente el máximo.", "35.6", now.minusHours(27), true);

        seedIrrigation(coffee, null, now.minusDays(1).minusHours(3), 30, "450", IrrigationType.AUTOMATIC);
        seedIrrigation(plantain, user, now.minusDays(2).minusHours(1), 45, "680", IrrigationType.MANUAL);
        seedIrrigation(coffee, null, now.minusDays(4), 25, "380", IrrigationType.AUTOMATIC);
        seedIrrigation(potato, user, now.minusDays(5).minusHours(6), 20, "240", IrrigationType.MANUAL);

        seedPrediction(coffee, "IRRIGATION", "0.8700", "Regar 30 minutos antes de las 6:00 a. m.");
        seedPrediction(plantain, "IRRIGATION", "0.6400", "No se requiere riego en las próximas 12 horas.");

        log.info("Sample data created for {}", seedEmail);
    }

    private User seedUser() {
        return userRepository.save(User.builder()
                .name("Usuario")
                .lastName("Demo")
                .email(seedEmail)
                .passwordHash(passwordEncoder.encode(seedPassword))
                .role("farmer")
                .build());
    }

    private Estate seedEstate(User user, String name, String location, String latitude, String longitude,
            String areaHa) {
        return estateRepository.save(Estate.builder()
                .user(user)
                .name(name)
                .location(location)
                .latitude(new BigDecimal(latitude))
                .longitude(new BigDecimal(longitude))
                .areaHa(new BigDecimal(areaHa))
                .build());
    }

    private Crop seedCrop(Estate estate, String name, String variety, int monthsSinceSowing, CropStage stage) {
        return cropRepository.save(Crop.builder()
                .estate(estate)
                .name(name)
                .variety(variety)
                .sowingDate(LocalDate.now().minusMonths(monthsSinceSowing))
                .stage(stage)
                .build());
    }

    /** Stores a sensor with one reading per hour that follows a daily wave around {@code base}. */
    private Sensor seedSensor(Crop crop, String code, SensorType type, String location, String unit,
            double base, double amplitude, LocalDateTime now) {
        Sensor sensor = sensorRepository.save(Sensor.builder()
                .crop(crop)
                .sensorCode(code)
                .sensorType(type)
                .location(location)
                .lastReadingAt(now)
                .build());
        List<SensorReading> readings = new ArrayList<>();
        for (int hour = READING_HOURS - 1; hour >= 0; hour--) {
            double wave = Math.sin((READING_HOURS - 1 - hour) / (double) READING_HOURS * Math.PI * 2);
            double ripple = Math.sin(hour * 12.9898 + code.hashCode()) * amplitude * 0.12;
            readings.add(SensorReading.builder()
                    .sensor(sensor)
                    .value(BigDecimal.valueOf(base + wave * amplitude + ripple).setScale(2, RoundingMode.HALF_UP))
                    .unit(unit)
                    .recordedAt(now.minusHours(hour))
                    .build());
        }
        readingRepository.saveAll(readings);
        return sensor;
    }

    private void seedAlert(Crop crop, Sensor sensor, AlertType type, Severity severity, String message,
            String detectedValue, LocalDateTime createdAt, boolean acknowledged) {
        alertRepository.save(Alert.builder()
                .crop(crop)
                .sensor(sensor)
                .alertType(type)
                .severity(severity)
                .message(message)
                .detectedValue(detectedValue == null ? null : new BigDecimal(detectedValue))
                .acknowledged(acknowledged)
                .createdAt(createdAt)
                .build());
    }

    private void seedIrrigation(Crop crop, User activatedBy, LocalDateTime startedAt, int durationMin,
            String liters, IrrigationType type) {
        irrigationRepository.save(Irrigation.builder()
                .crop(crop)
                .startedAt(startedAt)
                .endedAt(startedAt.plusMinutes(durationMin))
                .durationMin(durationMin)
                .waterLiters(new BigDecimal(liters))
                .type(type)
                .activatedBy(activatedBy)
                .build());
    }

    private void seedPrediction(Crop crop, String type, String confidence, String recommendation) {
        predictionRepository.save(AiPrediction.builder()
                .crop(crop)
                .type(type)
                .confidence(new BigDecimal(confidence))
                .recommendation(recommendation)
                .modelUsed("sample-data")
                .build());
    }
}
