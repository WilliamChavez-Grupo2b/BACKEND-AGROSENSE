package com.agrosense.backend;

import com.agrosense.backend.ai.service.FarmContextService;
import com.agrosense.backend.ai.service.FarmSnapshot;
import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.CropStage;
import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.AlertService;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FarmContextServiceTests {

	private static final String EMAIL = "ana@agrosense.co";

	private final CropRepository crops = mock(CropRepository.class);
	private final SensorRepository sensors = mock(SensorRepository.class);
	private final SensorReadingRepository readings = mock(SensorReadingRepository.class);
	private final AlertRepository alerts = mock(AlertRepository.class);
	private final AlertService alertService = mock(AlertService.class);
	private final IrrigationRepository irrigations = mock(IrrigationRepository.class);
	private final FarmContextService service = new FarmContextService(crops, sensors, readings, alerts, alertService,
			irrigations);

	@Test
	void theFarmComesWithEachCropsProfileItsAlertsOpenAndAttendedAndItsLatestIrrigations() {
		Crop coffee = crop(1, "Café");
		coffee.setVariety("Castillo");
		coffee.setStage(CropStage.FLOWERING);
		coffee.setSowingDate(LocalDate.of(2026, 3, 1));
		Estate estate = new Estate();
		estate.setName("El Roble");
		estate.setLocation("Pasto, Nariño");
		coffee.setEstate(estate);
		when(crops.findByEstateUserEmailAndActiveTrueOrderByNameAsc(EMAIL)).thenReturn(List.of(coffee));
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(1, SensorType.SOIL_MOISTURE, "OK"))
				.thenReturn(Optional.of(reading("22.5000")));
		when(alerts.countByCropEstateUserEmailAndAcknowledgedFalse(EMAIL)).thenReturn(1L);
		when(alertService.findOpen(EMAIL, FarmContextService.MAX_ALERTS)).thenReturn(List.of(AlertResponse.builder()
				.cropName("Café").message("Humedad baja").alertType("LOW_HUMIDITY").severity("HIGH")
				.detectedValue(new BigDecimal("22.5000")).createdAt(LocalDateTime.of(2026, 10, 15, 6, 30)).build()));
		Alert attended = new Alert();
		attended.setCrop(coffee);
		attended.setAlertType(AlertType.PH_OUT_OF_RANGE);
		attended.setSeverity(Severity.MEDIUM);
		attended.setMessage("pH fuera del rango del cultivo.");
		attended.setDetectedValue(new BigDecimal("4.9"));
		attended.setCreatedAt(LocalDateTime.of(2026, 10, 12, 8, 0));
		when(alerts.findByCropEstateUserEmailAndAcknowledgedTrueOrderByCreatedAtDesc(EMAIL,
				PageRequest.of(0, FarmContextService.MAX_ATTENDED_ALERTS))).thenReturn(List.of(attended));
		Irrigation watering = new Irrigation();
		watering.setCrop(coffee);
		watering.setStartedAt(LocalDateTime.of(2026, 10, 14, 5, 45));
		watering.setDurationMin(20);
		watering.setWaterLiters(new BigDecimal("150.00"));
		watering.setType(IrrigationType.MANUAL);
		watering.setReason("Suelo seco");
		when(irrigations.findByCropEstateUserEmailOrderByStartedAtDesc(EMAIL,
				PageRequest.of(0, FarmContextService.MAX_IRRIGATIONS))).thenReturn(List.of(watering));

		String described = service.snapshot(EMAIL).describe();

		assertThat(described).contains(
				"Cultivo Café (variedad Castillo, etapa floración, sembrado 2026-03-01, finca El Roble, Pasto, Nariño): "
						+ "humedad del suelo 22.5 % (rango 40–80 %).",
				"Alerta en Café (humedad baja, severidad alta, valor 22.5, 2026-10-15 06:30): Humedad baja.",
				"Alerta atendida en Café (pH fuera de rango, severidad media, valor 4.9, 2026-10-12 08:00): "
						+ "pH fuera del rango del cultivo.",
				"Riego manual en Café el 2026-10-14 05:45, 20 min, 150 L, motivo: Suelo seco.");
		verify(alerts).findByCropEstateUserEmailAndAcknowledgedTrueOrderByCreatedAtDesc(eq(EMAIL), any());
		verify(irrigations).findByCropEstateUserEmailOrderByStartedAtDesc(eq(EMAIL), any());
	}

	@Test
	void whatWasNotRecordedIsLeftOutAndALongDescriptionIsCutAtAWholeLine() {
		CropStatus bare = new CropStatus("Papa", null, null, null, null, null, null, null, null, null);
		assertThat(new FarmSnapshot(1, 0, 0, List.of(bare), List.of()).describe())
				.endsWith("Cultivo Papa: sin lecturas.")
				.doesNotContain("variedad", "etapa", "Riego", "atendida", "null");

		List<OpenAlert> many = IntStream.range(0, 200)
				.mapToObj(index -> new OpenAlert("Café", "Alerta número " + index + " con un texto largo de ejemplo."))
				.toList();
		String described = new FarmSnapshot(1, 0, 200, List.of(bare), many).describe();
		assertThat(described).hasSizeLessThanOrEqualTo(FarmSnapshot.MAX_DESCRIPTION_LENGTH)
				.endsWith("\n(Hay más datos de la finca que no caben aquí.)");
		assertThat(described.lines().filter(line -> line.startsWith("Alerta en")))
				.allMatch(line -> line.endsWith("con un texto largo de ejemplo."));
	}

	@Test
	void eachCropComesWithItsLatestReadingsItsOwnLimitsAndNothingMadeUp() {
		Crop rice = crop(1, "Arroz");
		rice.setHumidityMin(new BigDecimal("70.0"));
		rice.setHumidityMax(new BigDecimal("95.0"));
		Crop potato = crop(2, "Papa");
		when(crops.findByEstateUserEmailAndActiveTrueOrderByNameAsc(EMAIL)).thenReturn(List.of(rice, potato));
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(1, SensorType.SOIL_MOISTURE, "OK"))
				.thenReturn(Optional.of(reading("65.0000")));
		when(sensors.countByCropEstateUserEmailAndActiveTrue(EMAIL)).thenReturn(3L);
		when(alerts.countByCropEstateUserEmailAndAcknowledgedFalse(EMAIL)).thenReturn(7L);
		when(alertService.findOpen(EMAIL, FarmContextService.MAX_ALERTS)).thenReturn(List.of(
				AlertResponse.builder().cropName("Arroz").message("Humedad baja.").build()));

		FarmSnapshot farm = service.snapshot(EMAIL);

		assertThat(farm.totalCrops()).isEqualTo(2);
		assertThat(farm.totalSensors()).isEqualTo(3);
		assertThat(farm.pendingAlerts()).isEqualTo(7);
		CropStatus first = farm.crops().get(0);
		assertThat(first.name()).isEqualTo("Arroz");
		assertThat(first.humidity()).isEqualByComparingTo("65");
		assertThat(first.humidityMin()).isEqualByComparingTo("70");
		assertThat(first.humidityMax()).isEqualByComparingTo("95");
		assertThat(first.temperature()).isNull();
		assertThat(first.ph()).isNull();
		assertThat(first.phMin()).isEqualByComparingTo("5.5");
		assertThat(farm.crops().get(1).hasReadings()).isFalse();
		assertThat(farm.alerts()).singleElement().satisfies(alert -> {
			assertThat(alert.cropName()).isEqualTo("Arroz");
			assertThat(alert.message()).isEqualTo("Humedad baja.");
		});
	}

	@Test
	void aLargeFarmIsDescribedByItsFirstCropsAndCountedWhole() {
		List<Crop> many = IntStream.rangeClosed(1, 11).mapToObj(id -> crop(id, "Cultivo " + id)).toList();
		when(crops.findByEstateUserEmailAndActiveTrueOrderByNameAsc(EMAIL)).thenReturn(many);
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(alertService.findOpen(eq(EMAIL), anyInt())).thenReturn(List.of());

		FarmSnapshot farm = service.snapshot(EMAIL);

		assertThat(farm.totalCrops()).isEqualTo(11);
		assertThat(farm.crops()).hasSize(FarmContextService.MAX_CROPS);
		assertThat(farm.describe()).contains("Hay 3 cultivos más que no se detallan.");
		verify(readings, never()).findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(eq(9), any(), any());
	}

	private static Crop crop(int id, String name) {
		Crop crop = new Crop();
		crop.setIdCrop(id);
		crop.setName(name);
		return crop;
	}

	private static SensorReading reading(String value) {
		SensorReading reading = new SensorReading();
		reading.setValue(new BigDecimal(value));
		return reading;
	}
}
