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
import com.agrosense.backend.seed.DataSeeder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-seed;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.jpa.hibernate.ddl-auto=create-drop",
		"cors.allowed-origins=",
		"agrosense.seed.email=seed@agrosense.test",
		"agrosense.seed.password=seed-password"
})
@ActiveProfiles("seed")
class DataSeederTests {

	@Autowired
	private DataSeeder seeder;
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
	void seedsEveryEntityOnceAndHashesThePassword() {
		assertSeededCounts();

		User user = userRepository.findByEmail("seed@agrosense.test").orElseThrow();
		assertThat(user.getPasswordHash()).isNotEqualTo("seed-password");
		assertThat(passwordEncoder.matches("seed-password", user.getPasswordHash())).isTrue();
		assertThat(alertRepository.findAll()).anyMatch(alert -> alert.getCreatedAt().isBefore(
				java.time.LocalDateTime.now().minusHours(20)));

		// Running it again must not duplicate anything.
		seeder.run(new DefaultApplicationArguments());
		assertSeededCounts();
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
