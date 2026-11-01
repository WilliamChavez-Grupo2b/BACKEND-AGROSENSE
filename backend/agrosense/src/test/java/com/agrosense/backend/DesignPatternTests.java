package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.builder.PredictionRequest;
import com.agrosense.backend.pattern.creational.factory.alert.AlertContext;
import com.agrosense.backend.pattern.creational.factory.alert.AlertCreator;
import com.agrosense.backend.pattern.creational.factory.alert.FrostRiskAlertCreator;
import com.agrosense.backend.pattern.creational.factory.alert.SensorOfflineAlertCreator;
import com.agrosense.backend.pattern.creational.prototype.CropTemplate;
import com.agrosense.backend.pattern.creational.prototype.CropTemplateCatalog;
import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.pattern.creational.singleton.CalibrationRegistry;
import com.agrosense.backend.pattern.structural.adapter.OpenMeteoAdapter;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider.Outlook;
import com.agrosense.backend.pattern.structural.bridge.PendingIrrigationCommands;
import com.agrosense.backend.pattern.structural.decorator.BaseSensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.CalibrationDecorator;
import com.agrosense.backend.pattern.structural.decorator.SensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.pattern.structural.proxy.AiServiceProxy;
import com.agrosense.backend.security.DeviceRateLimiter;
import com.agrosense.backend.service.AiClientService;
import com.agrosense.backend.service.DeviceEventService;
import com.agrosense.backend.service.PredictionService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DesignPatternTests {

	private final Crop crop = Crop.builder().idCrop(1).name("Café").humidityMin(new BigDecimal("40"))
			.humidityMax(new BigDecimal("80")).tempMin(new BigDecimal("15")).tempMax(new BigDecimal("35"))
			.phMin(new BigDecimal("5.5")).phMax(new BigDecimal("7")).build();

	private static final class MovableClock extends Clock {
		private Instant now = Instant.parse("2026-11-01T12:00:00Z");

		void advance(Duration time) {
			now = now.plus(time);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneId.of("UTC");
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}
	}

	@AfterEach
	void forgetCalibrations() {
		CalibrationRegistry.INSTANCE.clear();
	}

	private Sensor sensor(SensorType type) {
		return Sensor.builder().sensorCode("AS-1").sensorType(type).crop(crop).build();
	}

	private Optional<Alert> alertFor(SensorType type, String value) {
		return AlertCreator.firstFor(AlertContext.ofReading(sensor(type), new BigDecimal(value), "u"));
	}

	@Test
	void singletonCalibrationRegistryIsOneInstanceAndReadsItsSettings() {
		assertThat(CalibrationRegistry.valueOf("INSTANCE")).isSameAs(CalibrationRegistry.INSTANCE);

		CalibrationRegistry.INSTANCE.load("ph=0.1:1.02, SOIL_MOISTURE=-1.5:1");

		assertThat(CalibrationRegistry.INSTANCE.of(SensorType.PH).apply(new BigDecimal("6"))).isEqualByComparingTo("6.22");
		assertThat(CalibrationRegistry.INSTANCE.of(SensorType.SOIL_MOISTURE).apply(new BigDecimal("30")))
				.isEqualByComparingTo("28.5");
		assertThat(CalibrationRegistry.INSTANCE.of(SensorType.LIGHT).apply(new BigDecimal("30")))
				.isEqualByComparingTo("30");
		assertThatThrownBy(() -> CalibrationRegistry.INSTANCE.load("PH=0.1")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> CalibrationRegistry.INSTANCE.load("PH=0:0")).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void prototypeHandsOutDeepCopiesSoTheCatalogueNeverChanges() {
		CropTemplateCatalog catalog = new CropTemplateCatalog();

		CropTemplate mine = catalog.cloneOf(" cafe ");
		mine.getHumidity().setMin(new BigDecimal("70"));
		mine.getPh().setMax(new BigDecimal("6"));

		CropTemplate next = catalog.cloneOf("CAFE");
		assertThat(next).isNotSameAs(mine);
		assertThat(next.getHumidity()).isNotSameAs(mine.getHumidity());
		assertThat(next.getHumidity().getMin()).isEqualByComparingTo("60");
		assertThat(next.getPh().getMax()).isEqualByComparingTo("6.5");

		Crop target = new Crop();
		mine.applyTo(target);
		assertThat(target.getHumidityMin()).isEqualByComparingTo("70");
		assertThat(target.getTempMax()).isEqualByComparingTo("24");
		assertThatThrownBy(() -> catalog.cloneOf("TRIGO")).isInstanceOf(BusinessRuleException.class);

		CropTemplate listed = catalog.all().stream().filter(item -> item.getKey().equals("TOMATE")).findFirst().orElseThrow();
		listed.getHumidity().setMin(BigDecimal.ONE);
		assertThat(catalog.cloneOf("tomate").getHumidity().getMin()).isEqualByComparingTo("60");
	}

	@Test
	void builderSendsOnlyWhatWasMeasuredAndRefusesBadRequests() {
		Map<String, Object> body = PredictionRequest.forCrop(7).soilMoisture(new BigDecimal("31.5"))
				.humidityRange(new BigDecimal("40"), new BigDecimal("80")).build().toBody();

		assertThat(body).containsOnlyKeys("id_crop", "soil_moisture", "humidity_min", "humidity_max");
		assertThatThrownBy(() -> PredictionRequest.forCrop(null).soilMoisture(BigDecimal.ONE).build())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> PredictionRequest.forCrop(7).build()).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> PredictionRequest.forCrop(7).soilMoisture(BigDecimal.ONE).ph(new BigDecimal("15")).build())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> PredictionRequest.forCrop(7).soilMoisture(BigDecimal.ONE)
				.humidityRange(new BigDecimal("80"), new BigDecimal("40")).build())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> PredictionRequest.forCrop(7).soilMoisture(BigDecimal.ONE)
				.phRange(new BigDecimal("5"), null).build()).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void factoryMethodMakesTheAlertOfEachRule() {
		assertThat(alertFor(SensorType.SOIL_MOISTURE, "25").orElseThrow().getAlertType()).isEqualTo(AlertType.LOW_HUMIDITY);
		assertThat(alertFor(SensorType.SOIL_MOISTURE, "90").orElseThrow().getAlertType()).isEqualTo(AlertType.HIGH_HUMIDITY);
		assertThat(alertFor(SensorType.AIR_TEMPERATURE, "41").orElseThrow().getAlertType())
				.isEqualTo(AlertType.HIGH_TEMPERATURE);
		assertThat(alertFor(SensorType.PH, "8").orElseThrow().getAlertType()).isEqualTo(AlertType.PH_OUT_OF_RANGE);
		assertThat(alertFor(SensorType.AIR_TEMPERATURE, "10").orElseThrow().getAlertType())
				.isEqualTo(AlertType.LOW_TEMPERATURE);
		assertThat(alertFor(SensorType.SOIL_MOISTURE, "60")).isEmpty();
		assertThat(alertFor(SensorType.RELATIVE_HUMIDITY, "5")).isEmpty();
	}

	@Test
	void frostWinsOverLowTemperatureButOnlyInTheAir() {
		Alert frost = alertFor(SensorType.AIR_TEMPERATURE, "1.5").orElseThrow();
		assertThat(frost.getAlertType()).isEqualTo(AlertType.FROST_RISK);
		assertThat(frost.getSeverity()).isEqualTo(Severity.VERY_HIGH);
		assertThat(alertFor(SensorType.SOIL_TEMPERATURE, "1.5").orElseThrow().getAlertType())
				.isEqualTo(AlertType.LOW_TEMPERATURE);

		Alert forecast = new FrostRiskAlertCreator().evaluate(AlertContext.ofForecast(crop, new BigDecimal("0.4"),
				LocalDate.parse("2026-11-02"))).orElseThrow();
		assertThat(forecast.getMessage()).contains("0.4 °C", "2026-11-02");
		assertThat(forecast.getSensor()).isNull();
		assertThat(new FrostRiskAlertCreator().evaluate(AlertContext.ofForecast(crop, new BigDecimal("9"),
				LocalDate.parse("2026-11-02")))).isEmpty();
	}

	@Test
	void aSilentDeviceRaisesAnOfflineAlertThatNamesIt() {
		Alert alert = new SensorOfflineAlertCreator().evaluate(AlertContext.ofSilentDevice(crop, "RPI-1",
				LocalDateTime.parse("2026-11-01T08:00:00.5"))).orElseThrow();

		assertThat(alert.getAlertType()).isEqualTo(AlertType.SENSOR_OFFLINE);
		assertThat(alert.getSeverity()).isEqualTo(Severity.HIGH);
		assertThat(alert.getMessage()).contains("RPI-1", "2026-11-01T08:00");
		assertThat(new SensorOfflineAlertCreator().evaluate(AlertContext.ofSilentDevice(crop, "RPI-1", null))).isEmpty();
	}

	@Test
	void adapterTurnsOpenMeteoArraysIntoTheColdestDay() throws IOException {
		AtomicReference<String> query = new AtomicReference<>();
		AtomicReference<String> answer = new AtomicReference<>("""
				{"daily":{"time":["2026-11-02","2026-11-03"],"temperature_2m_min":[6.5,1.2],
				"precipitation_probability_max":[20,80],"precipitation_sum":[0.5,6.5]}}""");
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			query.set(exchange.getRequestURI().toString());
			byte[] body = answer.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		try {
			OpenMeteoAdapter adapter = new OpenMeteoAdapter("http://127.0.0.1:" + server.getAddress().getPort(), 2000);

			Outlook outlook = adapter.outlook(new BigDecimal("4.71"), new BigDecimal("-74.07")).orElseThrow();

			assertThat(query.get()).contains("/v1/forecast", "latitude=4.71", "longitude=-74.07", "temperature_2m_min",
					"precipitation_probability_max");
			assertThat(outlook.coldestDay()).isEqualTo(LocalDate.parse("2026-11-03"));
			assertThat(outlook.minimumTemperature()).isEqualByComparingTo("1.2");
			assertThat(outlook.rainProbability()).isEqualTo(80);
			assertThat(outlook.rainMm()).isEqualByComparingTo("7.0");

			String first = query.get();
			adapter.outlook(new BigDecimal("4.71"), new BigDecimal("-74.07"));
			assertThat(query.get()).isSameAs(first);

			answer.set("{\"daily\":{\"time\":[\"2026-11-02\"],\"temperature_2m_min\":[null]}}");
			assertThat(adapter.outlook(BigDecimal.ONE, BigDecimal.ONE)).isEmpty();
			answer.set("no es json");
			assertThat(adapter.outlook(BigDecimal.TEN, BigDecimal.ONE)).isEmpty();
		} finally {
			server.stop(0);
		}
	}

	@Test
	void decoratorCorrectsTheReadingBeforeItIsValidated() {
		CalibrationRegistry.INSTANCE.register(SensorType.PH,
				new CalibrationRegistry.Calibration(new BigDecimal("-1"), BigDecimal.ONE));
		SensorReadingProcessor chain = new ValidationDecorator(new CalibrationDecorator(new BaseSensorReadingProcessor()));

		SensorReading corrected = chain.process(SensorReading.builder().sensor(sensor(SensorType.PH))
				.value(new BigDecimal("14.5")).quality("OK").build());

		assertThat(corrected.getValue()).isEqualByComparingTo("13.5");
		assertThat(corrected.getQuality()).isEqualTo("OK");
	}

	private DeviceEventRequest readingEvent() {
		DeviceEventRequest request = new DeviceEventRequest();
		request.setDeviceId("RPI-1");
		request.setType("reading");
		return request;
	}

	private static Map<String, Object> stored(String sensorType) {
		Map<String, Object> event = new LinkedHashMap<>();
		event.put("cropId", 1);
		event.put("readings", List.of(SensorReadingResponse.builder().sensorType(sensorType).build()));
		Map<String, Object> answer = new LinkedHashMap<>();
		answer.put("ownerEmail", "demo@agrosense.co");
		answer.put("event", event);
		return answer;
	}

	@Test
	void facadeStoresTheEventAndAsksForOnePredictionPerInterval() {
		DeviceEventService events = mock(DeviceEventService.class);
		PredictionService predictions = mock(PredictionService.class);
		AiConfigManager ai = mock(AiConfigManager.class);
		when(ai.isEnabled()).thenReturn(true);
		when(events.receive(any(), any(), any(), any(), any())).thenReturn(stored("SOIL_MOISTURE"));
		when(predictions.currentConditions("demo@agrosense.co", 1)).thenReturn(Map.of("soil_moisture", 30));
		MovableClock clock = new MovableClock();
		DeviceIngestionFacade facade = new DeviceIngestionFacade(events, predictions, ai, mock(PendingIrrigationCommands.class),
				new DeviceRateLimiter(0), Duration.ofMinutes(30), clock);

		facade.ingest("key", readingEvent());
		facade.ingest("key", readingEvent());
		verify(predictions, times(1)).requestIrrigationPrediction(eq(1), any());

		clock.advance(Duration.ofMinutes(30));
		Map<String, Object> answer = facade.ingest("key", readingEvent());
		verify(predictions, times(2)).requestIrrigationPrediction(eq(1), any());
		assertThat(answer).containsKey("ownerEmail");
	}

	@Test
	void facadeNeverLosesAStoredEventOverAPrediction() {
		DeviceEventService events = mock(DeviceEventService.class);
		PredictionService predictions = mock(PredictionService.class);
		AiConfigManager ai = mock(AiConfigManager.class);
		when(ai.isEnabled()).thenReturn(true);
		when(events.receive(any(), any(), any(), any(), any())).thenReturn(stored("SOIL_MOISTURE"));
		when(predictions.currentConditions(any(), any())).thenThrow(new IllegalStateException("database down"));
		DeviceIngestionFacade facade = new DeviceIngestionFacade(events, predictions, ai, mock(PendingIrrigationCommands.class),
				new DeviceRateLimiter(0), Duration.ofMinutes(30),
				new MovableClock());

		assertThat(facade.ingest("key", readingEvent())).containsKey("event");

		when(events.receive(any(), any(), any(), any(), any())).thenReturn(stored("PH"));
		DeviceIngestionFacade noMoisture = new DeviceIngestionFacade(events, predictions, ai, mock(PendingIrrigationCommands.class),
				new DeviceRateLimiter(0), Duration.ofMinutes(30),
				new MovableClock());
		noMoisture.ingest("key", readingEvent());
		verify(predictions, never()).requestIrrigationPrediction(any(), any());
	}

	private final AiClientService aiClient = mock(AiClientService.class);
	private final AiConfigManager enabledAi = enabledAi();

	private static AiConfigManager enabledAi() {
		AiConfigManager manager = mock(AiConfigManager.class);
		when(manager.getSettings()).thenReturn(new AiConfigManager.Settings("http://ai.test", 1000, true, 1));
		return manager;
	}

	@Test
	void proxyAnswersRepeatedConditionsFromItsCacheUntilTheyExpire() {
		MovableClock clock = new MovableClock();
		AiServiceProxy proxy = new AiServiceProxy(aiClient, enabledAi, clock);
		Map<String, Object> conditions = Map.of("soil_moisture", 30);
		IrrigationPrediction answer = new IrrigationPrediction("Regar", new BigDecimal("0.9"), "m1");
		when(aiClient.predictIrrigation(1, conditions)).thenReturn(Optional.of(answer));

		assertThat(proxy.predictIrrigation(1, conditions)).contains(answer);
		assertThat(proxy.predictIrrigation(1, conditions)).contains(answer);
		verify(aiClient, times(1)).predictIrrigation(1, conditions);

		clock.advance(AiServiceProxy.CACHE_FOR);
		proxy.predictIrrigation(1, conditions);
		verify(aiClient, times(2)).predictIrrigation(1, conditions);
	}

	@Test
	void proxyFallsBackToLocalRulesAndLeavesAFailingServiceAlone() {
		MovableClock clock = new MovableClock();
		AiServiceProxy proxy = new AiServiceProxy(aiClient, enabledAi, clock);
		Map<String, Object> dry = Map.of("soil_moisture", new BigDecimal("30"), "humidity_min", new BigDecimal("40"));
		when(aiClient.predictIrrigation(any(), any())).thenThrow(new IllegalStateException("down"));

		IrrigationPrediction local = proxy.predictIrrigation(1, dry).orElseThrow();
		assertThat(local.modelUsed()).isEqualTo(AiServiceProxy.LOCAL_MODEL);
		assertThat(local.recommendation()).startsWith("Riega");
		assertThat(proxy.predictIrrigation(1, Map.of("soil_moisture", 70)).orElseThrow().recommendation())
				.startsWith("No hace falta regar");
		assertThat(proxy.predictIrrigation(1, Map.of())).isEmpty();
		verify(aiClient, times(AiServiceProxy.FAILURES_TO_PAUSE)).predictIrrigation(any(), any());

		assertThat(proxy.predictIrrigation(1, dry).orElseThrow().modelUsed()).isEqualTo(AiServiceProxy.LOCAL_MODEL);
		verify(aiClient, times(AiServiceProxy.FAILURES_TO_PAUSE)).predictIrrigation(any(), any());

		clock.advance(AiServiceProxy.PAUSE);
		proxy.predictIrrigation(1, dry);
		verify(aiClient, times(AiServiceProxy.FAILURES_TO_PAUSE + 1)).predictIrrigation(any(), any());
	}
}
