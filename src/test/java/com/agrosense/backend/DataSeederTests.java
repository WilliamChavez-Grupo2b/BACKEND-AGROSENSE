package com.agrosense.backend;

import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.AiPredictionRepository;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** Starts like a real "seed" run: tables and rows come from /database, entities are only validated. */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-seed;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"agrosense.seed.password=seed-password"
})
@ActiveProfiles("seed")
class DataSeederTests {

	@Autowired
	private DataSource dataSource;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private EstateRepository estateRepository;
	@Autowired
	private CropRepository cropRepository;
	@Autowired
	private SensorRepository sensorRepository;
	@Autowired
	private SensorReadingRepository readingRepository;
	@Autowired
	private AlertRepository alertRepository;
	@Autowired
	private IrrigationRepository irrigationRepository;
	@Autowired
	private AiPredictionRepository predictionRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void seedScriptLoadsEveryEntityOnceAndTheDemoPasswordIsSetByTheApplication() {
		assertSeededCounts();

		User user = userRepository.findByEmail("demo@agrosense.co").orElseThrow();
		assertThat(passwordEncoder.matches("seed-password", user.getPasswordHash())).isTrue();
		assertThat(alertRepository.findAll())
				.anyMatch(alert -> alert.getCreatedAt().isBefore(LocalDateTime.now().minusHours(20)))
				.anyMatch(alert -> alert.getSensor() == null);
		assertThat(cropRepository.findAll()).allMatch(crop -> crop.getHumidityMin() != null && crop.getPhMax() != null);
		assertThat(readingRepository.findAll())
				.allMatch(reading -> !reading.getRecordedAt().isAfter(LocalDateTime.now().plusMinutes(1)));

		// Applying both scripts again must not duplicate or change anything.
		new ResourceDatabasePopulator(
				new FileSystemResource("../database/schema.sql"),
				new FileSystemResource("../database/seed_demo.sql")).execute(dataSource);
		assertSeededCounts();
		assertThat(passwordEncoder.matches("seed-password",
				userRepository.findByEmail("demo@agrosense.co").orElseThrow().getPasswordHash())).isTrue();
	}

	private void assertSeededCounts() {
		assertThat(userRepository.count()).isEqualTo(1);
		assertThat(estateRepository.count()).isEqualTo(2);
		assertThat(cropRepository.count()).isEqualTo(3);
		assertThat(sensorRepository.count()).isEqualTo(6);
		assertThat(readingRepository.count()).isEqualTo(5 * 24);
		assertThat(alertRepository.count()).isEqualTo(4);
		assertThat(irrigationRepository.count()).isEqualTo(4);
		assertThat(predictionRepository.count()).isEqualTo(2);
	}
}
