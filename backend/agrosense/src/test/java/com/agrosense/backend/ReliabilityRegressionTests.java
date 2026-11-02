package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.DispatchStatus;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.config.MissingSchemaFailureAnalyzer;
import com.agrosense.backend.dto.request.IrrigationRequest;
import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.IrrigationDispatch;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;
import com.agrosense.backend.pattern.creational.factory.alert.SensorOfflineAlertCreator;
import com.agrosense.backend.pattern.structural.bridge.IrrigationCommand;
import com.agrosense.backend.pattern.structural.bridge.PendingIrrigationCommands;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.IrrigationDispatchRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.security.DeviceRateLimiter;
import com.agrosense.backend.service.IrrigationService;
import com.agrosense.backend.service.PredictionService;
import com.agrosense.backend.service.ReadingRetentionService;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

/** What used to go wrong under odd input and at the same moment, each case with a real server and the real schema. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-reliability;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver",
		"cors.allowed-origins=",
		"jwt.secret=test-secret-that-is-at-least-64-bytes-long-for-hs512-signatures-0123456789",
		"agrosense.seed.password=seed-password",
		"agrosense.seed.device-key=demo-device-key-for-tests",
		"events.internal-key=internal-key-of-the-events",
		"events.stream-secret=stream-secret-of-at-least-32-bytes",
		"login.max-attempts-per-address=0",
		"devices.max-events-per-minute=0",
		"alerts.frost-watch=false"
})
@ActiveProfiles("seed")
class ReliabilityRegressionTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String DEVICE = "RPI-DEMO-01";
	private static final String KEY = "demo-device-key-for-tests";
	private static final String INTERNAL_KEY = "internal-key-of-the-events";

	@Value("${local.server.port}")
	private int port;

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private DeviceRepository devices;
	@Autowired
	private CropRepository crops;
	@Autowired
	private SensorRepository sensors;
	@Autowired
	private SensorReadingRepository readings;
	@Autowired
	private IrrigationRepository irrigations;
	@Autowired
	private IrrigationDispatchRepository dispatches;
	@Autowired
	private AlertRepository alerts;
	@Autowired
	private PendingIrrigationCommands pending;
	@Autowired
	private IrrigationService irrigationService;
	@Autowired
	private PredictionService predictions;
	@Autowired
	private SensorFacade sensorFacade;
	@Autowired
	private PlatformTransactionManager transactions;

	private final HttpClient client = HttpClient.newHttpClient();

	private HttpResponse<String> event(String body) throws Exception {
		return client.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/internal/events"))
				.header("Content-Type", "application/json").header("X-Internal-Key", INTERNAL_KEY)
				.header("X-Device-Key", KEY).POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String reading(String ts, String data) {
		return "{\"device_id\":\"" + DEVICE + "\",\"type\":\"reading\"" + (ts == null ? "" : ",\"ts\":" + ts)
				+ ",\"data\":" + data + "}";
	}

	private Integer crop() {
		return jdbc.queryForObject("SELECT id_crop FROM devices WHERE device_code = ?", Integer.class, DEVICE);
	}

	private Sensor sensorOf(Integer crop, SensorType type) {
		return sensors.findByCropIdCropAndActiveTrue(crop).stream().filter(sensor -> sensor.getSensorType() == type)
				.findFirst().orElseThrow();
	}

	private <T> List<T> atTheSameTime(int threads, Callable<T> work) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<T>> running = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			running.add(pool.submit(() -> {
				start.await();
				return work.call();
			}));
		}
		start.countDown();
		List<T> results = new ArrayList<>();
		for (Future<T> result : running) {
			results.add(result.get(60, TimeUnit.SECONDS));
		}
		pool.shutdown();
		return results;
	}

	@Test
	void anImpossibleReadingNeitherBlocksAnIrrigationNorReachesTheAi() throws Exception {
		Integer crop = crop();
		assertThat(event(reading(null, "{\"soil_moisture\":41.5}")).statusCode()).isEqualTo(200);
		assertThat(event(reading(null, "{\"soil_moisture\":1500}")).statusCode()).isEqualTo(200);

		IrrigationRequest request = new IrrigationRequest();
		request.setCropId(crop);
		request.setDurationMinutes(5);
		request.setWaterLiters(new BigDecimal("10"));
		request.setReason("Tras una lectura imposible");

		Irrigation stored = irrigations.findById(irrigationService.register(EMAIL, request).getIdIrrigation()).orElseThrow();
		assertThat(stored.getMoistureBefore()).isEqualByComparingTo("41.5");
		assertThat(new BigDecimal(predictions.currentConditions(EMAIL, crop).get("soil_moisture").toString()))
				.isEqualByComparingTo("41.5");
	}

	@Test
	void theMoistureAfterAnIrrigationIsOneMeasuredAfterIt() {
		Integer cropId = crop();
		Crop crop = crops.findById(cropId).orElseThrow();
		Sensor soil = sensorOf(cropId, SensorType.SOIL_MOISTURE);
		LocalDateTime ended = LocalDateTime.now().minusMinutes(31);
		Irrigation irrigation = irrigations.save(Irrigation.builder().crop(crop).startedAt(ended.minusMinutes(5))
				.endedAt(ended).durationMin(5).moistureBefore(new BigDecimal("30")).build());
		jdbc.update("DELETE FROM sensor_readings WHERE id_sensor = ? AND recorded_at > ?", soil.getIdSensor(),
				ended.minusHours(1));

		irrigationService.recordResults();
		assertThat(irrigations.findById(irrigation.getIdIrrigation()).orElseThrow().getMoistureAfter())
				.as("the device went silent: nothing was measured after the water").isNull();

		readings.save(SensorReading.builder().sensor(soil).value(new BigDecimal("55")).quality("OK").unit("%")
				.recordedAt(LocalDateTime.now().minusMinutes(1)).build());
		irrigationService.recordResults();
		assertThat(irrigations.findById(irrigation.getIdIrrigation()).orElseThrow().getMoistureAfter())
				.isEqualByComparingTo("55");
	}

	@Test
	void whatADeviceCannotMeanIsRefusedAndNeverAnInternalError() throws Exception {
		Map<String, String> events = new java.util.LinkedHashMap<>();
		events.put("a time too big for a number", reading("1e30", "{\"ph\":6.5}"));
		events.put("a huge integer time", reading("123456789012345678901234567890", "{\"ph\":6.5}"));
		events.put("a time before the device existed", reading("12", "{\"ph\":6.5}"));
		events.put("a negative time", reading("-5", "{\"ph\":6.5}"));
		events.put("an old date", reading("\"1999-01-01T00:00:00Z\"", "{\"ph\":6.5}"));
		events.put("a value that does not fit", reading(null, "{\"ph\":1e12}"));
		events.put("an astronomical value", reading(null, "{\"ph\":1e400}"));
		events.put("a value with a huge exponent", reading(null, "{\"ph\":1e999999}"));
		events.put("a negative value that does not fit", reading(null, "{\"ph\":-1e12}"));
		events.put("a value in text", reading(null, "{\"ph\":\"6.5\"}"));
		events.put("a null value", reading(null, "{\"ph\":null}"));
		events.put("no data", "{\"device_id\":\"" + DEVICE + "\",\"type\":\"reading\"}");
		events.put("data in a list", reading(null, "[1,2]"));
		events.put("no type", "{\"device_id\":\"" + DEVICE + "\",\"data\":{\"ph\":6.5}}");
		events.put("the wrong body", "[]");
		events.put("no body at all", "null");
		events.put("not json", "{\"device_id\":");
		for (Map.Entry<String, String> impossible : events.entrySet()) {
			HttpResponse<String> answer = event(impossible.getValue());
			assertThat(answer.statusCode()).as(impossible.getKey()).isEqualTo(400);
			assertThat(answer.body()).as(impossible.getKey()).contains("\"message\"").doesNotContain("Exception", "at com.");
		}
	}

	@Test
	void anAckThatMakesNoSenseStillStoresTheReading() throws Exception {
		for (String ack : List.of("{\"command_id\":\"x\",\"result\":\"finished\"}", "{\"command_id\":99999999999999}",
				"{\"command_id\":999999,\"result\":\"nonsense\"}", "{}",
				"{\"command_id\":1,\"result\":\"finished\",\"ran_seconds\":99999999999999}")) {
			HttpResponse<String> answer = event("{\"device_id\":\"" + DEVICE + "\",\"type\":\"reading\",\"ack\":" + ack
					+ ",\"data\":{\"ph\":6.4}}");
			assertThat(answer.statusCode()).as(ack).isEqualTo(200);
		}
	}

	@Test
	void aRunLongerThanADayIsNotBelieved() {
		Integer crop = crop();
		Irrigation irrigation = irrigations.save(Irrigation.builder().crop(crops.findById(crop).orElseThrow())
				.startedAt(LocalDateTime.now()).endedAt(LocalDateTime.now().plusMinutes(5)).durationMin(5).build());
		pending.offer(new IrrigationCommand(crop, 5, IrrigationCommand.MANUAL, null, irrigation.getIdIrrigation()));
		IrrigationCommand delivered = pending.take(crop).orElseThrow();

		assertThat(pending.confirm(crop, delivered.id(), PendingIrrigationCommands.FINISHED, Integer.MAX_VALUE)).isFalse();
		assertThat(dispatches.findById(delivered.id()).orElseThrow().getStatus()).isEqualTo(DispatchStatus.DELIVERED);
	}

	@Test
	void anAckCannotTouchTheOrderOfAnotherCrop() {
		Integer crop = crop();
		pending.offer(new IrrigationCommand(crop, 5, IrrigationCommand.MANUAL, null, null));
		IrrigationCommand delivered = pending.take(crop).orElseThrow();

		assertThat(pending.confirm(crop + 1, delivered.id(), PendingIrrigationCommands.REFUSED, null)).isFalse();
		assertThat(dispatches.findById(delivered.id()).orElseThrow().getStatus()).isEqualTo(DispatchStatus.DELIVERED);
	}

	@Test
	void twoCopiesOfAnEventAtTheSameTimeStoreOneReading() throws Exception {
		Sensor ph = sensorOf(crop(), SensorType.PH);
		for (int round = 0; round < 5; round++) {
			String ts = String.valueOf(1793000000L + round);
			List<Integer> statuses = atTheSameTime(8, () -> event(reading(ts, "{\"ph\":6.1}")).statusCode());
			assertThat(statuses).containsOnly(200);
			LocalDateTime when = LocalDateTime.ofInstant(java.time.Instant.ofEpochSecond(Long.parseLong(ts)),
					java.time.ZoneId.systemDefault());
			assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings WHERE id_sensor = ? AND recorded_at = ?",
					Integer.class, ph.getIdSensor(), when)).as("round " + round).isEqualTo(1);
		}
	}

	@Test
	void anOrderIsHandedOutOnceHoweverManyAskAtTheSameTime() throws Exception {
		Integer crop = crop();
		for (int round = 0; round < 5; round++) {
			pending.offer(new IrrigationCommand(crop, 3, IrrigationCommand.MANUAL, null, null));
			List<Optional<IrrigationCommand>> taken = atTheSameTime(12, () -> pending.take(crop));
			assertThat(taken.stream().filter(Optional::isPresent).count()).as("round " + round).isEqualTo(1);
		}
	}

	@Test
	void anOrderDeliveredWhileItWasBeingReplacedStaysDelivered() {
		Integer crop = crop();
		pending.offer(new IrrigationCommand(crop, 3, IrrigationCommand.MANUAL, null, null));
		Integer first = dispatches.findFirstByCropIdAndStatusOrderByIdDispatchDesc(crop, DispatchStatus.PENDING)
				.orElseThrow().getIdDispatch();

		// offer() has read the order as pending when the device's answer takes it, in its own transaction.
		TransactionTemplate own = new TransactionTemplate(transactions);
		own.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		IrrigationDispatchRepository racing = Mockito.mock(IrrigationDispatchRepository.class,
				AdditionalAnswers.delegatesTo(dispatches));
		doAnswer(call -> {
			List<IrrigationDispatch> read = dispatches.findByCropIdAndStatus(crop, DispatchStatus.PENDING);
			runElsewhere(() -> own.executeWithoutResult(status -> dispatches.claim(first, LocalDateTime.now())));
			return read;
		}).when(racing).findByCropIdAndStatus(crop, DispatchStatus.PENDING);

		PendingIrrigationCommands replacing = new PendingIrrigationCommands(racing, irrigations);
		new TransactionTemplate(transactions).executeWithoutResult(status ->
				replacing.offer(new IrrigationCommand(crop, 4, IrrigationCommand.MANUAL, null, null)));

		assertThat(dispatches.findById(first).orElseThrow().getStatus())
				.as("the device has it: only its own report may change it").isEqualTo(DispatchStatus.DELIVERED);
		dispatches.findByCropIdAndStatus(crop, DispatchStatus.PENDING).forEach(dispatches::delete);
	}

	private static void runElsewhere(Runnable work) throws InterruptedException {
		Thread thread = new Thread(work);
		thread.start();
		thread.join(30_000);
	}

	@Test
	void aSilenceRaisesOneAlertHoweverManyWatchesSeeItAtOnce() throws Exception {
		Device device = devices.findByDeviceCode(DEVICE).orElseThrow();
		LocalDateTime last = LocalDateTime.now().minusHours(2);
		Crop crop = crops.findWithEstateByIdCrop(crop()).orElseThrow();
		jdbc.update("DELETE FROM alerts WHERE id_crop = ? AND alert_type = 'SENSOR_OFFLINE'", crop.getIdCrop());
		SensorOfflineAlertCreator creator = new SensorOfflineAlertCreator();
		atTheSameTime(8, () -> new TransactionTemplate(transactions).execute(status -> sensorFacade.raiseOnceSince(creator,
				AlertContext.ofSilentDevice(crops.findWithEstateByIdCrop(crop.getIdCrop()).orElseThrow(),
						device.getDeviceCode(), last), last)));
		assertThat(alerts.findAll().stream().filter(alert -> alert.getAlertType() == AlertType.SENSOR_OFFLINE
				&& alert.getCrop().getIdCrop().equals(crop.getIdCrop())).count()).isEqualTo(1);
	}

	@Test
	void aStrangerCannotUseUpTheAllowanceOfARealDevice() {
		DeviceRateLimiter limiter = new DeviceRateLimiter(3, Clock.systemUTC());
		for (int attempt = 0; attempt < 3; attempt++) {
			limiter.countFailure(DEVICE);
		}
		assertThatThrownBy(() -> limiter.countFailure(DEVICE)).isInstanceOf(ApiException.class);
		limiter.count(DEVICE);  // its own key: the wrong ones never touched its allowance
		limiter.count(DEVICE);
		limiter.count(DEVICE);
		assertThatThrownBy(() -> limiter.count(DEVICE)).isInstanceOf(ApiException.class);
	}

	@Test
	void inventedDeviceCodesCannotFillTheMemory() {
		DeviceRateLimiter limiter = new DeviceRateLimiter(3, Clock.systemUTC());
		for (int code = 0; code < 200_000; code++) {
			limiter.countFailure("INVENTED-" + code);
		}
		assertThat(limiter.tracked()).isLessThanOrEqualTo(DeviceRateLimiter.MAX_TRACKED);
	}

	@Test
	void anAskedForBodyOfAGigabyteIsRefusedBeforeItIsRead() throws Exception {
		HttpResponse<String> answer = client.send(HttpRequest.newBuilder(
						URI.create("http://localhost:" + port + "/api/auth/login"))
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + "a".repeat(2_000_000) + "\"}")).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(answer.statusCode()).isEqualTo(413);
		assertThat(answer.body()).contains("\"message\"");
	}

	@Test
	void aMissingTableExplainsWhichMigrationToRun() {
		FailureAnalysis analysis = new MissingSchemaFailureAnalyzer().analyze(new IllegalStateException("boot",
				new SchemaManagementException("Schema validation: missing table [irrigation_dispatches]")));
		assertThat(analysis.getDescription()).contains("irrigation_dispatches");
		assertThat(analysis.getAction()).contains("database/migrations", "database/schema.sql");
	}

	@Test
	void retentionTurnedOffDeletesNothing() {
		long before = readings.count();
		new ReadingRetentionService(jdbc, 0).compact();
		assertThat(readings.count()).isEqualTo(before);
	}

	@Test
	void retentionInterruptedAfterSummarisingLeavesEverythingAsItWas() {
		Sensor sensor = sensorOf(crop(), SensorType.AIR_TEMPERATURE);
		readings.save(SensorReading.builder().sensor(sensor).value(new BigDecimal("21")).quality("OK").unit("°C")
				.recordedAt(LocalDateTime.now().minusDays(60)).build());
		long raw = readings.count();
		Long summaries = jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings_hourly", Long.class);

		JdbcTemplate failing = Mockito.spy(jdbc);
		doThrow(new DataAccessResourceFailureException("the connection dropped"))
				.when(failing).update(startsWith("DELETE"), any(Object[].class));
		ReadingRetentionService interrupted = new ReadingRetentionService(failing, 30);

		assertThatThrownBy(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> interrupted.compact()))
				.isInstanceOf(DataAccessResourceFailureException.class);

		assertThat(readings.count()).isEqualTo(raw);
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings_hourly", Long.class)).isEqualTo(summaries);
		jdbc.update("DELETE FROM sensor_readings WHERE recorded_at < ?", LocalDateTime.now().minusDays(50));
	}
}
