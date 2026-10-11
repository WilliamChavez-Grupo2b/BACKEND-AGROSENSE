package com.agrosense.backend;

import com.agrosense.backend.config.DemoDataInitializer;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/** The demo data, on the schema from /database, when it is switched on with a password. */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-demo-data;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"spring.sql.init.mode=always",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"app.demo.enabled=true",
		"app.demo.email= Admin@AgroSense.io ",
		"app.demo.password=a-password-from-the-environment"
})
class DemoDataInitializerTests {

	@Autowired
	private DemoDataInitializer initializer;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private EstateRepository estateRepository;
	@Autowired
	private CropRepository cropRepository;
	@Autowired
	private SensorRepository sensorRepository;
	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void anAdministratorWithAnEstateACropAndSensorsIsCreatedOnce() {
		User admin = userRepository.findByEmail("admin@agrosense.io").orElseThrow();
		assertThat(admin.getRole()).isEqualTo("admin");
		assertThat(admin.getActive()).isTrue();
		assertThat(passwordEncoder.matches("a-password-from-the-environment", admin.getPasswordHash())).isTrue();

		List<Crop> crops = cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(admin.getEmail());
		assertThat(estateRepository.findByUserEmailOrderByNameAsc(admin.getEmail())).hasSize(1);
		assertThat(crops).hasSize(1);
		assertThat(sensorRepository.findByCropIdCropOrderBySensorCodeAsc(crops.get(0).getIdCrop()))
				.extracting(Sensor::getSensorCode)
				.containsExactly("ESP32-001-HS", "ESP32-001-LU", "ESP32-001-PH", "ESP32-001-TA");

		// A restart runs it again; nothing is duplicated.
		long users = userRepository.count();
		long sensors = sensorRepository.count();
		initializer.run();
		assertThat(userRepository.count()).isEqualTo(users);
		assertThat(sensorRepository.count()).isEqualTo(sensors);
	}

	@Test
	void switchingItOnWithoutAPasswordStopsTheApplication() {
		DemoDataInitializer withoutPassword = new DemoDataInitializer(mock(UserRepository.class),
				mock(EstateRepository.class), mock(CropRepository.class), mock(SensorRepository.class),
				new SensorFactory(), mock(PasswordEncoder.class), "admin@agrosense.io", " ");

		assertThatThrownBy(withoutPassword::run).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("DEMO_PASSWORD");
	}
}
