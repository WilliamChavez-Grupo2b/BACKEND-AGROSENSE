package com.agrosense.backend.config;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

/**
 * Creates an administrator with one estate, one crop and four sensors, so a new installation has
 * something to show. Off unless {@code app.demo.enabled=true}; when on, it needs a password from the
 * environment, because an account with a password that ships in the source is open to anyone.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.demo.enabled", havingValue = "true")
public class DemoDataInitializer implements CommandLineRunner {

    private static final Map<String, SensorType> SENSORS = Map.of(
            "ESP32-001-HS", SensorType.SOIL_MOISTURE,
            "ESP32-001-TA", SensorType.AIR_TEMPERATURE,
            "ESP32-001-PH", SensorType.PH,
            "ESP32-001-LU", SensorType.LIGHT);

    private final UserRepository userRepository;
    private final EstateRepository estateRepository;
    private final CropRepository cropRepository;
    private final SensorRepository sensorRepository;
    private final SensorFactory sensorFactory;
    private final PasswordEncoder passwordEncoder;
    private final String demoEmail;
    private final String demoPassword;

    public DemoDataInitializer(UserRepository userRepository, EstateRepository estateRepository,
            CropRepository cropRepository, SensorRepository sensorRepository, SensorFactory sensorFactory,
            PasswordEncoder passwordEncoder,
            @Value("${app.demo.email:admin@agrosense.io}") String demoEmail,
            @Value("${app.demo.password:}") String demoPassword) {
        this.userRepository = userRepository;
        this.estateRepository = estateRepository;
        this.cropRepository = cropRepository;
        this.sensorRepository = sensorRepository;
        this.sensorFactory = sensorFactory;
        this.passwordEncoder = passwordEncoder;
        this.demoEmail = demoEmail.trim().toLowerCase(Locale.ROOT);
        this.demoPassword = demoPassword;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (demoPassword.isBlank()) {
            throw new IllegalStateException("app.demo.enabled=true requires DEMO_PASSWORD to be set");
        }
        if (userRepository.existsByEmail(demoEmail)) {
            log.info("Los datos de demostración ya existen; no se crean de nuevo.");
            return;
        }

        User admin = userRepository.save(User.builder()
                .name("Admin")
                .lastName("AgroSense")
                .email(demoEmail)
                .passwordHash(passwordEncoder.encode(demoPassword))
                .role("admin")
                .build());
        Estate estate = estateRepository.save(Estate.builder()
                .user(admin)
                .name("Finca El Paraíso")
                .location("Palmira, Valle del Cauca, Colombia")
                .latitude(new BigDecimal("3.5394"))
                .longitude(new BigDecimal("-76.3018"))
                .areaHa(new BigDecimal("5.5"))
                .build());
        Crop tomato = cropRepository.save(Crop.builder()
                .estate(estate)
                .name("Tomate")
                .variety("Chonto")
                .sowingDate(LocalDate.of(2026, 1, 15))
                .humidityMin(new BigDecimal("60.0"))
                .humidityMax(new BigDecimal("80.0"))
                .tempMin(new BigDecimal("18.0"))
                .tempMax(new BigDecimal("30.0"))
                .phMin(new BigDecimal("5.8"))
                .phMax(new BigDecimal("6.8"))
                .build());
        // Sensor codes are unique across the system; one already taken by someone else is left alone.
        SENSORS.forEach((code, type) -> {
            if (!sensorRepository.existsBySensorCode(code)) {
                sensorRepository.save(sensorFactory.create(type, code, null, tomato));
            }
        });

        // The password is never written to the log.
        log.info("Datos de demostración creados para {}", demoEmail);
    }
}
