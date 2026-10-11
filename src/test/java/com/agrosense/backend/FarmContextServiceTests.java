package com.agrosense.backend;

import com.agrosense.backend.ai.service.FarmContextService;
import com.agrosense.backend.ai.service.FarmSnapshot;
import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.AlertService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

/** What the assistant is told about a farm: only the caller's, with each crop's readings and limits. */
class FarmContextServiceTests {

	private static final String EMAIL = "ana@agrosense.co";

	private final CropRepository crops = mock(CropRepository.class);
	private final SensorRepository sensors = mock(SensorRepository.class);
	private final SensorReadingRepository readings = mock(SensorReadingRepository.class);
	private final AlertRepository alerts = mock(AlertRepository.class);
	private final AlertService alertService = mock(AlertService.class);
	private final FarmContextService service = new FarmContextService(crops, sensors, readings, alerts, alertService);

	@Test
	void eachCropComesWithItsLatestReadingsItsOwnLimitsAndNothingMadeUp() {
		Crop rice = crop(1, "Arroz");
		rice.setHumidityMin(new BigDecimal("70.0"));
		rice.setHumidityMax(new BigDecimal("95.0"));
		Crop potato = crop(2, "Papa");
		when(crops.findByEstateUserEmailAndActiveTrueOrderByNameAsc(EMAIL)).thenReturn(List.of(rice, potato));
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(any(), any()))
				.thenReturn(Optional.empty());
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(1, SensorType.SOIL_MOISTURE))
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
		when(readings.findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(any(), any()))
				.thenReturn(Optional.empty());
		when(alertService.findOpen(eq(EMAIL), anyInt())).thenReturn(List.of());

		FarmSnapshot farm = service.snapshot(EMAIL);

		assertThat(farm.totalCrops()).isEqualTo(11);
		assertThat(farm.crops()).hasSize(FarmContextService.MAX_CROPS);
		assertThat(farm.describe()).contains("Hay 3 cultivos más que no se detallan.");
		// The crops left out are not read at all.
		verify(readings, never()).findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(eq(9), any());
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
