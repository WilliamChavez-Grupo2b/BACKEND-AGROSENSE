package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.DispatchStatus;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.request.CropRequest;
import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.dto.request.IrrigationRequest;
import com.agrosense.backend.dto.response.CropResponse;
import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider.Outlook;
import com.agrosense.backend.pattern.structural.bridge.IrrigationCommand;
import com.agrosense.backend.pattern.structural.bridge.PendingIrrigationCommands;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.IrrigationDispatchRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.EstateCropService;
import com.agrosense.backend.service.FieldWatchService;
import com.agrosense.backend.service.IrrigationService;
import com.agrosense.backend.service.PredictionService;
import com.agrosense.backend.websocket.SensorWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Against the real schema: its CHECK has to accept the new alert types, and the orders have to live in its tables. */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:h2:mem:agrosense-field-watch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1",
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
		"devices.max-events-per-minute=5",
		"readings.retention-days=30"
})
@ActiveProfiles("seed")
class FieldWatchIntegrationTests {

	private static final String EMAIL = "demo@agrosense.co";
	private static final String DEVICE = "ESP32-PLANTICAS";
	private static final String KEY = "demo-device-key-for-tests";
	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Autowired
	private FieldWatchService watch;
	@Autowired
	private DeviceRepository devices;
	@Autowired
	private AlertRepository alerts;
	@Autowired
	private EstateRepository estates;
	@Autowired
	private EstateCropService crops;
	@Autowired
	private CropRepository cropRepository;
	@Autowired
	private SensorRepository sensors;
	@Autowired
	private SensorReadingRepository readings;
	@Autowired
	private SensorFacade sensorFacade;
	@Autowired
	private DeviceIngestionFacade ingestion;
	@Autowired
	private IrrigationDispatchRepository dispatches;
	@Autowired
	private PendingIrrigationCommands pending;
	@Autowired
	private IrrigationRepository irrigations;
	@Autowired
	private IrrigationService irrigationService;
	@Autowired
	private PredictionService predictions;
	@Autowired
	private com.agrosense.backend.service.ReadingRetentionService retention;
	@Autowired
	private JdbcTemplate jdbc;
	@MockitoBean
	private WeatherProvider weather;
	@MockitoSpyBean
	private SimpMessagingTemplate messaging;

	private long allOf(AlertType type) {
		return alerts.findAll().stream().filter(alert -> alert.getAlertType() == type).count();
	}

	private void answerAll(AlertType type) {
		alerts.findAll().stream().filter(alert -> alert.getAlertType() == type).forEach(alert -> {
			alert.setAcknowledged(true);
			alerts.save(alert);
		});
	}

	private Integer cropOfTheDevice() {
		return jdbc.queryForObject("SELECT id_crop FROM devices WHERE device_code = ?", Integer.class, DEVICE);
	}

	private Sensor firstSensor(SensorType type) {
		return sensors.findByCropIdCropAndActiveTrue(cropOfTheDevice()).stream()
				.filter(sensor -> sensor.getSensorType() == type).findFirst().orElseThrow();
	}

	private static DeviceEventRequest event(String device, String json) {
		DeviceEventRequest request = new DeviceEventRequest();
		request.setDeviceId(device);
		request.setType("reading");
		JsonNode body = JSON.readTree(json);
		request.setTs(body.get("ts"));
		request.setData(body.get("data"));
		request.setAck(body.get("ack"));
		return request;
	}

	@Test
	void aDeviceThatStoppedReportingRaisesOneAlertAndItStaysAnsweredOnceAnswered() {
		watch.watchSilentDevices();
		assertThat(allOf(AlertType.SENSOR_OFFLINE)).as("a device that never reported is not silent").isZero();

		Device device = devices.findByDeviceCode(DEVICE).orElseThrow();
		device.setLastEventAt(LocalDateTime.now().minusHours(1));
		devices.save(device);

		watch.watchSilentDevices();
		watch.watchSilentDevices();
		assertThat(allOf(AlertType.SENSOR_OFFLINE)).isEqualTo(1);

		answerAll(AlertType.SENSOR_OFFLINE);
		watch.watchSilentDevices();
		assertThat(allOf(AlertType.SENSOR_OFFLINE)).as("still the same silence").isEqualTo(1);
	}

	@Test
	void aForecastFrostRaisesAnAlertOnTheCropsOfTheEstateAndDoesNotRepeatEachHour() {
		when(weather.outlook(any(), any())).thenReturn(
				Optional.of(new Outlook(LocalDate.now().plusDays(1), new BigDecimal("0.5"), 10, BigDecimal.ZERO)));

		watch.watchFrost();
		long raised = allOf(AlertType.FROST_RISK);
		watch.watchFrost();
		answerAll(AlertType.FROST_RISK);
		watch.watchFrost();

		assertThat(raised).isPositive();
		assertThat(allOf(AlertType.FROST_RISK)).isEqualTo(raised);
	}

	@Test
	void aFailingForecastDoesNotStopTheRound() {
		when(weather.outlook(any(), any())).thenThrow(new IllegalStateException("weather down"));
		watch.watchFrost();
	}

	@Test
	void aCropStartsFromItsTemplateAndItsOwnRangesWin() {
		CropRequest request = new CropRequest();
		request.setEstateId(estates.findByUserEmailOrderByNameAsc(EMAIL).get(0).getIdEstate());
		request.setName("Café de prueba");
		request.setTemplate("cafe");
		request.setPhMin(new BigDecimal("4.5"));
		request.setPhMax(new BigDecimal("6.0"));

		CropResponse crop = crops.createCrop(EMAIL, request);

		assertThat(crop.getHumidityMin()).isEqualByComparingTo("60");
		assertThat(crop.getPhMin()).isEqualByComparingTo("4.5");
	}

	@Test
	void anOrderIsDeliveredOnceAndItsResultIsRecorded() {
		Integer crop = cropOfTheDevice();
		pending.offer(new IrrigationCommand(crop, 5, IrrigationCommand.AI, new BigDecimal("0.8"), null));
		pending.offer(new IrrigationCommand(crop, 7, IrrigationCommand.MANUAL, null, null));

		IrrigationCommand first = pending.take(crop).orElseThrow();
		assertThat(first.minutes()).as("the newest order replaces the older one").isEqualTo(7);
		assertThat(pending.take(crop)).as("never twice").isEmpty();

		assertThat(pending.confirm(crop + 1000, first.id(), "started", null)).as("not another crop's").isFalse();
		assertThat(pending.confirm(crop, first.id(), "nonsense", null)).isFalse();
		assertThat(pending.confirm(crop, first.id(), "started", null)).isTrue();
		assertThat(pending.confirm(crop, first.id(), "refused", null)).as("only once").isFalse();
		assertThat(dispatches.findById(first.id()).orElseThrow().getStatus()).isEqualTo(DispatchStatus.STARTED);
		assertThat(dispatches.findById(first.id()).orElseThrow().getConfirmedAt()).isNotNull();
	}

	private Irrigation irrigationOfFiveMinutes(Integer crop) {
		IrrigationRequest request = new IrrigationRequest();
		request.setCropId(crop);
		request.setDurationMinutes(5);
		request.setWaterLiters(new BigDecimal("100"));
		request.setReason("Prueba");
		return irrigations.findById(irrigationService.register(EMAIL, request).getIdIrrigation()).orElseThrow();
	}

	private IrrigationCommand delivered(Irrigation irrigation, Integer crop) {
		pending.offer(new IrrigationCommand(crop, 5, IrrigationCommand.MANUAL, null, irrigation.getIdIrrigation()));
		return pending.take(crop).orElseThrow();
	}

	@Test
	void theHistoryKeepsWhatTheDeviceReallyRan() {
		Integer crop = cropOfTheDevice();
		Irrigation irrigation = irrigationOfFiveMinutes(crop);
		IrrigationCommand command = delivered(irrigation, crop);

		assertThat(pending.confirm(crop, command.id(), "started", null)).isTrue();
		assertThat(pending.confirm(crop, command.id(), "finished", 90)).isTrue();

		Irrigation real = irrigations.findById(irrigation.getIdIrrigation()).orElseThrow();
		assertThat(real.getDurationMin()).isEqualTo(2);
		assertThat(real.getWaterLiters()).isEqualByComparingTo("30.00");
		assertThat(java.time.Duration.between(real.getStartedAt(), real.getEndedAt()).getSeconds()).isEqualTo(90);
		assertThat(dispatches.findById(command.id()).orElseThrow().getRanSeconds()).isEqualTo(90);
		assertThat(pending.confirm(crop, command.id(), "finished", 90)).as("only once").isFalse();
	}

	@Test
	void anOrderThatNeverRanLeavesNoWaterInTheHistory() {
		Integer crop = cropOfTheDevice();
		Irrigation refused = irrigationOfFiveMinutes(crop);
		pending.confirm(crop, delivered(refused, crop).id(), "refused", null);

		Irrigation record = irrigations.findById(refused.getIdIrrigation()).orElseThrow();
		assertThat(record.getWaterLiters()).isEqualByComparingTo("0");
		assertThat(record.getDurationMin()).isNull();
		assertThat(record.getEndedAt()).isEqualTo(record.getStartedAt());
		assertThat(record.getReason()).contains("rechazado por el dispositivo");

		Irrigation late = irrigationOfFiveMinutes(crop);
		pending.offer(new IrrigationCommand(crop, 5, IrrigationCommand.MANUAL, null, late.getIdIrrigation()));
		jdbc.update("UPDATE irrigation_dispatches SET expires_at = ? WHERE status = 'PENDING'",
				LocalDateTime.now().minusSeconds(1));
		assertThat(pending.take(crop)).isEmpty();
		assertThat(irrigations.findById(late.getIdIrrigation()).orElseThrow().getReason()).contains("caducó");
	}

	@Test
	void anOrderThatWaitedTooLongExpires() {
		Integer crop = cropOfTheDevice();
		pending.offer(new IrrigationCommand(crop, 5, IrrigationCommand.MANUAL, null, null));
		jdbc.update("UPDATE irrigation_dispatches SET expires_at = ? WHERE status = 'PENDING'",
				LocalDateTime.now().minusSeconds(1));

		assertThat(pending.take(crop)).isEmpty();
		assertThat(dispatches.findAll()).noneMatch(dispatch -> dispatch.getStatus() == DispatchStatus.PENDING);
	}

	@Test
	void theDeviceIsAuthenticatedEachWayAndALimitProtectsTheRoute() {
		String reading = "{\"data\":{\"ph\":6.4}}";

		assertThatThrownBy(() -> ingestion.ingest(" ", event("LIMIT-A", reading)))
				.isInstanceOfSatisfying(ApiException.class, failure ->
						assertThat(failure.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
		assertThatThrownBy(() -> ingestion.ingest("other-devices-key", event(DEVICE, reading)))
				.isInstanceOfSatisfying(ApiException.class, failure ->
						assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
		assertThatThrownBy(() -> ingestion.ingest(KEY, event("NOT-A-DEVICE", reading)))
				.isInstanceOfSatisfying(ApiException.class, failure ->
						assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));

		Device device = devices.findByDeviceCode(DEVICE).orElseThrow();
		device.setActive(false);
		devices.save(device);
		try {
			assertThatThrownBy(() -> ingestion.ingest(KEY, event(DEVICE, reading)))
					.isInstanceOfSatisfying(ApiException.class, failure ->
							assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT));
		} finally {
			device.setActive(true);
			devices.save(device);
		}

		for (int attempt = 0; attempt < 5; attempt++) {
			assertThatThrownBy(() -> ingestion.ingest("x", event("LIMIT-B", reading)))
					.isInstanceOfSatisfying(ApiException.class, failure ->
							assertThat(failure.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
		}
		assertThatThrownBy(() -> ingestion.ingest("x", event("LIMIT-B", reading)))
				.isInstanceOfSatisfying(ApiException.class, failure ->
						assertThat(failure.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
	}

	@Test
	void aRetriedEventDoesNotDuplicateItsReadings() {
		Sensor ph = firstSensor(SensorType.PH);
		String stamped = "{\"ts\":1793000000,\"data\":{\"ph\":6.4}}";
		long before = readings.count();

		ingestion.ingest(KEY, event(DEVICE, stamped));
		ingestion.ingest(KEY, event(DEVICE, stamped));

		assertThat(readings.count()).isEqualTo(before + 1);
		assertThat(ph.getSensorCode()).isNotBlank();
	}

	@Test
	void theCalibrationChangesTheValueThatIsStored() {
		String code = firstSensor(SensorType.PH).getSensorCode();
		BigDecimal raw = new BigDecimal("6");
		SensorReading before = sensorFacade.record(SensorReadingData.builder().sensorCode(code).value(raw)
				.recordedAt(LocalDateTime.now().plusDays(3)).build()).reading();
		assertThat(before.getValue()).as("neutral by default").isEqualByComparingTo("6.00");

		CalibrationRegistry.INSTANCE.load("PH=0.1:1.02");
		try {
			SensorReading after = sensorFacade.record(SensorReadingData.builder().sensorCode(code).value(raw)
					.recordedAt(LocalDateTime.now().plusDays(3).plusMinutes(1)).build()).reading();
			assertThat(after.getValue()).as("6 x 1.02 + 0.1").isEqualByComparingTo("6.22");
			assertThat(readings.findById(after.getIdReading()).orElseThrow().getValue()).isEqualByComparingTo("6.22");
		} finally {
			CalibrationRegistry.INSTANCE.clear();
		}
	}

	@Test
	void anEventReachesTheWebSocketOfItsOwnerWithItsAlert() {
		ingestion.ingest(KEY, event(DEVICE, "{\"ts\":1793000100,\"data\":{\"air_temperature\":79}}"));

		verify(messaging).convertAndSendToUser(eq(EMAIL), eq(SensorWebSocketHandler.READINGS_QUEUE), any(Object.class));
		verify(messaging, atLeastOnce()).convertAndSendToUser(eq(EMAIL), eq(SensorWebSocketHandler.ALERTS_QUEUE),
				any(Object.class));
	}

	@Test
	void anImpossibleOrStuckValueRaisesASensorFault() {
		Sensor moisture = firstSensor(SensorType.SOIL_MOISTURE);
		for (int reading = 0; reading < 10; reading++) {
			sensorFacade.record(SensorReadingData.builder().sensorCode(moisture.getSensorCode())
					.value(new BigDecimal("100")).recordedAt(LocalDateTime.now().plusSeconds(reading)).build());
		}
		assertThat(allOf(AlertType.SENSOR_FAULT)).isEqualTo(1);
		assertThat(alerts.findAll().stream().filter(alert -> alert.getAlertType() == AlertType.SENSOR_FAULT)
				.findFirst().orElseThrow().getMessage()).contains("10 lecturas");

		answerAll(AlertType.SENSOR_FAULT);
		sensorFacade.record(SensorReadingData.builder().sensorCode(moisture.getSensorCode())
				.value(new BigDecimal("140")).recordedAt(LocalDateTime.now().plusMinutes(1)).build());
		assertThat(allOf(AlertType.SENSOR_FAULT)).isEqualTo(2);
	}

	@Test
	void anIrrigationNotesTheMoistureItLeftAndTeachesTheGainPerMinute() {
		Integer cropId = cropOfTheDevice();
		Crop crop = cropRepository.findById(cropId).orElseThrow();
		IrrigationRequest request = new IrrigationRequest();
		request.setCropId(cropId);
		request.setDurationMinutes(10);
		request.setWaterLiters(new BigDecimal("50"));
		request.setReason("Prueba");
		sensorFacade.record(SensorReadingData.builder().sensorCode(firstSensor(SensorType.SOIL_MOISTURE).getSensorCode())
				.value(new BigDecimal("30")).recordedAt(LocalDateTime.now().plusHours(1)).build());
		Integer id = irrigationService.register(EMAIL, request).getIdIrrigation();
		Irrigation irrigation = irrigations.findById(id).orElseThrow();
		assertThat(irrigation.getMoistureBefore()).isEqualByComparingTo("30");

		irrigation.setStartedAt(LocalDateTime.now().minusHours(3));
		irrigation.setEndedAt(LocalDateTime.now().minusHours(2));
		irrigations.save(irrigation);
		sensorFacade.record(SensorReadingData.builder().sensorCode(firstSensor(SensorType.SOIL_MOISTURE).getSensorCode())
				.value(new BigDecimal("50")).recordedAt(LocalDateTime.now().plusHours(2)).build());

		irrigationService.recordResults();

		assertThat(irrigations.findById(id).orElseThrow().getMoistureAfter()).isEqualByComparingTo("50");
		assertThat(irrigationService.moistureGainPerMinute(cropId)).isEqualByComparingTo("2.0000");
		assertThat(crop.getName()).isNotBlank();
		assertThat(predictions.currentConditions(EMAIL, cropId)).containsKey("moisture_gain_per_minute");
	}

	@Test
	void oldReadingsBecomeHourlySummaries() {
		Integer sensor = firstSensor(SensorType.AIR_TEMPERATURE).getIdSensor();
		LocalDateTime old = LocalDateTime.now().minusDays(40).withMinute(10);
		readings.save(SensorReading.builder().sensor(sensors.findById(sensor).orElseThrow())
				.value(new BigDecimal("20")).recordedAt(old).quality("OK").unit("°C").build());
		readings.save(SensorReading.builder().sensor(sensors.findById(sensor).orElseThrow())
				.value(new BigDecimal("24")).recordedAt(old.plusMinutes(5)).quality("OK").unit("°C").build());

		retention.compact();

		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sensor_readings WHERE recorded_at < ?", Long.class,
				LocalDateTime.now().minusDays(30))).isZero();
		Map<String, Object> summary = jdbc.queryForMap(
				"SELECT avg_value, samples FROM sensor_readings_hourly WHERE id_sensor = ? ORDER BY hour_start DESC LIMIT 1",
				sensor);
		assertThat(new BigDecimal(summary.get("avg_value").toString())).isEqualByComparingTo("22");
		assertThat(summary.get("samples")).isEqualTo(2);
	}

	@Test
	void schemaAndMigrationsAllowEveryAlertType() throws Exception {
		Path database = Path.of("../../database");
		List<String> types = Arrays.stream(AlertType.values()).map(type -> "'" + type.name() + "'").toList();
		assertThat(Files.readString(database.resolve("schema.sql"))).contains(types);
		assertThat(Files.readString(database.resolve("migrations/002_alert_types.sql"))).contains(types);
		assertThat(Duration.ofMinutes(5)).isEqualTo(PendingIrrigationCommands.EXPIRES_AFTER);
	}
}
