package com.agrosense.backend.config;

import com.agrosense.backend.domain.enums.TipoSensor;
import com.agrosense.backend.domain.model.*;
import com.agrosense.backend.pattern.creacional.factory.SensorFactory;
import com.agrosense.backend.pattern.creacional.prototype.LecturaPrototype;
import com.agrosense.backend.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class DemoDataInitializer implements CommandLineRunner {

    private final UsuarioRepository userRepo;
    private final FincaRepository farmRepo;
    private final CultivoRepository cropRepo;
    private final SensorRepository sensorRepo;
    private final SensorFactory sensorFactory;
    private final LecturaPrototype readingPrototype;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.demo.enabled:true}")
    private boolean demoEnabled;

    @Value("${app.demo.email:admin@agrosense.io}")
    private String demoEmail;

    @Value("${app.demo.password:AgroSense2026!}")
    private String demoPassword;

    @Override
    public void run(String... args) {
        if (!demoEnabled) return;
        if (userRepo.existsByEmail(demoEmail)) {
            log.info("Demo data already exists, skipping initialization.");
            readingPrototype.inicializarPrototipos();
            return;
        }

        log.info("Creating demo data...");

        Usuario admin = Usuario.builder()
            .nombre("Admin")
            .apellido("AgroSense")
            .email(demoEmail)
            .passwordHash(passwordEncoder.encode(demoPassword))
            .rol("ADMIN")
            .activo(true)
            .build();
        userRepo.save(admin);

        Finca farm = Finca.builder()
            .usuario(admin)
            .nombre("El Paraiso Farm")
            .ubicacion("Palmira, Valle del Cauca, Colombia")
            .latitud(new BigDecimal("3.5394"))
            .longitud(new BigDecimal("-76.3018"))
            .areaHa(new BigDecimal("5.5"))
            .build();
        farmRepo.save(farm);

        Cultivo tomato = Cultivo.builder()
            .finca(farm)
            .nombreCultivo("Tomato")
            .variedad("Chonto")
            .fechaSiembra(LocalDate.of(2026, 1, 15))
            .humedadMin(new BigDecimal("60.0"))
            .humedadMax(new BigDecimal("80.0"))
            .tempMin(new BigDecimal("18.0"))
            .tempMax(new BigDecimal("30.0"))
            .phMin(new BigDecimal("5.8"))
            .phMax(new BigDecimal("6.8"))
            .activo(true)
            .build();
        cropRepo.save(tomato);

        Sensor humidity = sensorFactory.crear(
            TipoSensor.HUMEDAD_SUELO, "ESP32-001-HS", "North Zone", tomato);
        Sensor temperature = sensorFactory.crear(
            TipoSensor.TEMPERATURA_AIRE, "ESP32-001-TA", "Center", tomato);
        Sensor ph = sensorFactory.crear(
            TipoSensor.PH, "ESP32-001-PH", "South Zone", tomato);
        Sensor light = sensorFactory.crear(
            TipoSensor.LUMINOSIDAD, "ESP32-001-LU", "Roof", tomato);

        sensorRepo.save(humidity);
        sensorRepo.save(temperature);
        sensorRepo.save(ph);
        sensorRepo.save(light);

        readingPrototype.inicializarPrototipos();

        log.info("Demo data created. Login: {} / {}", demoEmail, demoPassword);
    }
}
