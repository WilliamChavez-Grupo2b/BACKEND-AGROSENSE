package com.agrosense.backend;

import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.repository.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The schema script, the sample data, the entity mappings and the row lock on a real PostgreSQL; the rest
 * of the suite runs on H2 emulating it. Skipped unless POSTGRES_TEST_URL is set, for example:
 *
 * <pre>
 * POSTGRES_TEST_URL=jdbc:postgresql://localhost:5432/postgres
 * POSTGRES_TEST_USERNAME=postgres
 * POSTGRES_TEST_PASSWORD=...
 * </pre>
 *
 * Everything happens inside a schema created for the run and dropped at the end, so the database the
 * URL points at is left as it was.
 */
@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(properties = {
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"agrosense.seed.password=seed-password"
})
@ActiveProfiles("seed")
class PostgresCompatibilityTests {

	private static final String SCHEMA = "agrosense_test_" + Long.toHexString(System.nanoTime());

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
	private AlertRepository alertRepository;
	@Autowired
	private IrrigationRepository irrigationRepository;
	@Autowired
	private SensorFacade sensorFacade;

	@Value("${agrosense.database.dir}")
	private String databaseDir;

	@DynamicPropertySource
	static void useAPrivateSchema(DynamicPropertyRegistry registry) throws SQLException {
		String url = System.getenv("POSTGRES_TEST_URL");
		execute("CREATE SCHEMA " + SCHEMA);
		registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
		registry.add("spring.datasource.username", PostgresCompatibilityTests::username);
		registry.add("spring.datasource.password", PostgresCompatibilityTests::password);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@AfterAll
	static void dropThePrivateSchema() throws SQLException {
		execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
	}

	/** Starting the context already applied schema.sql and seed_demo.sql and validated every entity. */
	@Test
	void schemaSampleDataAndEntitiesAgreeOnPostgres() {
		assertThat(userRepository.findByEmail("demo@agrosense.co")).isPresent();
		assertThat(sensorRepository.findBySensorCode("AS-001")).isPresent();
		// Applying the script again, as a restart with SQL_INIT_MODE=always does, changes nothing.
		assertThatCode(() -> new ResourceDatabasePopulator(new FileSystemResource(databaseDir + "/schema.sql"))
				.execute(dataSource)).doesNotThrowAnyException();
	}

	@Test
	void irrigationReasonsAreStored() {
		Crop crop = sensorRepository.findBySensorCode("AS-001").orElseThrow().getCrop();

		Irrigation saved = irrigationRepository.save(Irrigation.builder()
				.crop(crop).type(IrrigationType.MANUAL).durationMin(10).waterLiters(new BigDecimal("12.50"))
				.reason("Suelo seco").build());

		assertThat(irrigationRepository.findById(saved.getIdIrrigation()).orElseThrow().getReason())
				.isEqualTo("Suelo seco");
	}

	@Test
	void readingsArrivingTogetherRaiseASingleAlert() throws Exception {
		User owner = userRepository.findByEmail("demo@agrosense.co").orElseThrow();
		Estate estate = estateRepository.save(Estate.builder().user(owner).name("Lock test estate").build());
		Crop crop = cropRepository.save(Crop.builder().estate(estate).name("Lock test crop").build());
		sensorRepository.save(Sensor.builder().crop(crop).sensorCode("PG-RACE-1")
				.sensorType(SensorType.SOIL_MOISTURE).build());
		int simultaneous = 8;
		ExecutorService pool = Executors.newFixedThreadPool(simultaneous);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> results = new ArrayList<>();
		try {
			for (int i = 0; i < simultaneous; i++) {
				results.add(pool.submit(() -> {
					start.await();
					// Below the crop's minimum humidity (40): every one of them wants to raise the alert.
					return sensorFacade.recordReading(SensorReadingData.builder()
							.sensorCode("PG-RACE-1").value(new BigDecimal("10")).build());
				}));
			}
			start.countDown();
			for (Future<?> result : results) {
				result.get(30, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdownNow();
		}

		assertThat(alertRepository.findByCropIdCropAndAcknowledgedFalse(crop.getIdCrop())).hasSize(1);
	}

	private static void execute(String sql) throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				System.getenv("POSTGRES_TEST_URL"), username(), password());
				Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private static String username() {
		String username = System.getenv("POSTGRES_TEST_USERNAME");
		return username == null ? "postgres" : username;
	}

	private static String password() {
		String password = System.getenv("POSTGRES_TEST_PASSWORD");
		return password == null ? "" : password;
	}
}
