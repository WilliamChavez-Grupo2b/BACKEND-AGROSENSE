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
import java.sql.ResultSet;
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

@EnabledIfEnvironmentVariable(named = "POSTGRES_TEST_URL", matches = ".+")
@SpringBootTest(properties = {
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"agrosense.seed.password=seed-password"
})
@ActiveProfiles("seed")
class PostgresCompatibilityTests {

	private static final String SCHEMA = "agrosense_test_" + Long.toHexString(System.nanoTime());
	private static final String SELECT_SCHEMA = "SET search_path TO " + SCHEMA;

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
		requireThePrivateSchema();
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.hikari.connection-init-sql", () -> SELECT_SCHEMA);
		registry.add("spring.datasource.username", PostgresCompatibilityTests::username);
		registry.add("spring.datasource.password", PostgresCompatibilityTests::password);
		registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
	}

	@AfterAll
	static void dropThePrivateSchema() throws SQLException {
		execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
	}

	@Test
	void schemaSampleDataAndEntitiesAgreeOnPostgres() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertThat(connection.getSchema()).isEqualTo(SCHEMA);
		}
		assertThat(userRepository.findByEmail("demo@agrosense.co")).isPresent();
		assertThat(sensorRepository.findBySensorCode("AS-001")).isPresent();
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

	private static void requireThePrivateSchema() throws SQLException {
		try (Connection connection = DriverManager.getConnection(
				System.getenv("POSTGRES_TEST_URL"), username(), password());
				Statement statement = connection.createStatement()) {
			statement.execute(SELECT_SCHEMA);
			try (ResultSet current = statement.executeQuery("select current_schema()")) {
				current.next();
				if (!SCHEMA.equals(current.getString(1))) {
					throw new IllegalStateException("Expected schema " + SCHEMA + " but the connection uses "
							+ current.getString(1) + "; refusing to run against it");
				}
			}
		}
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
