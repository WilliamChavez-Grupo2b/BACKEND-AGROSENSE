package com.agrosense.backend;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.response.SensorResponse;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.AiPrediction;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.pattern.structural.proxy.AiServiceProxy;
import com.agrosense.backend.repository.AiPredictionRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.AiClientService;
import com.agrosense.backend.service.PrediccionService;
import com.agrosense.backend.service.SensorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ServiceTests {

	private static final String OWNER = "farmer@agrosense.test";

	private final AiClientService aiClient = mock(AiClientService.class);
	private final AiConfigManager aiConfig = AiConfigManager.getInstance();

	@Test
	void theProxySkipsADisabledServiceRetriesFailuresAndNeverThrows() {
		AiConfigManager.Settings previous = aiConfig.getSettings();
		try {
			AiServiceProxy proxy = new AiServiceProxy(aiClient, aiConfig);
			IrrigationPrediction prediction = new IrrigationPrediction("Regar 20 minutos", new BigDecimal("0.9100"), "m1");

			aiConfig.configure("", 1000, false, 3);
			assertThat(proxy.predictIrrigation(1, Map.of())).isEmpty();
			verify(aiClient, never()).predictIrrigation(any(), any());

			aiConfig.configure("http://ai.test", 1000, true, 3);
			when(aiClient.predictIrrigation(1, Map.of()))
					.thenThrow(new IllegalStateException("down"))
					.thenReturn(Optional.of(prediction));
			assertThat(proxy.predictIrrigation(1, Map.of())).contains(prediction);
			verify(aiClient, times(2)).predictIrrigation(1, Map.of());

			when(aiClient.predictIrrigation(2, Map.of())).thenThrow(new IllegalStateException("down"));
			assertThat(proxy.predictIrrigation(2, Map.of())).isEmpty();
			verify(aiClient, times(3)).predictIrrigation(2, Map.of());
		} finally {
			aiConfig.configure(previous.serviceUrl(), previous.timeoutMs(), previous.enabled(), previous.maxAttempts());
		}
	}

	@Test
	void predictionsAreStoredOnlyForExistingCropsAndOnlyWhenTheAiAnswers() {
		AiServiceProxy proxy = mock(AiServiceProxy.class);
		AiPredictionRepository predictions = mock(AiPredictionRepository.class);
		CropRepository crops = mock(CropRepository.class);
		PrediccionService service = new PrediccionService(proxy, predictions, crops,
				mock(SensorReadingRepository.class));
		Crop crop = crop(7);

		service.requestIrrigationPrediction(99, Map.of());
		verify(proxy, never()).predictIrrigation(any(), any());

		when(crops.existsById(7)).thenReturn(true);
		when(crops.getReferenceById(7)).thenReturn(crop);
		when(proxy.predictIrrigation(7, Map.of())).thenReturn(Optional.empty());
		service.requestIrrigationPrediction(7, Map.of());
		verify(predictions, never()).save(any());

		when(proxy.predictIrrigation(7, Map.of()))
				.thenReturn(Optional.of(new IrrigationPrediction("Regar 20 minutos", new BigDecimal("0.9100"), "m1")));
		service.requestIrrigationPrediction(7, Map.of());

		ArgumentCaptor<AiPrediction> saved = ArgumentCaptor.forClass(AiPrediction.class);
		verify(predictions).save(saved.capture());
		assertThat(saved.getValue().getCrop()).isSameAs(crop);
		assertThat(saved.getValue().getType()).isEqualTo("IRRIGATION");
		assertThat(saved.getValue().getRecommendation()).isEqualTo("Regar 20 minutos");
		assertThat(saved.getValue().getConfidence()).isEqualByComparingTo("0.91");
		assertThat(saved.getValue().getModelUsed()).isEqualTo("m1");
	}

	@Test
	void sensorsAreCreatedAndListedOnlyOnTheCallersOwnCrops() {
		SensorRepository sensors = mock(SensorRepository.class);
		CropRepository crops = mock(CropRepository.class);
		SensorService service = new SensorService(mock(SensorFacade.class), new SensorFactory(), sensors,
				mock(SensorReadingRepository.class), crops);
		Crop crop = crop(7);
		when(crops.findByIdCropAndEstateUserEmail(7, OWNER)).thenReturn(Optional.of(crop));
		when(crops.findByIdCropAndEstateUserEmail(7, "other@agrosense.test")).thenReturn(Optional.empty());
		when(sensors.save(any(Sensor.class))).thenAnswer(invocation -> invocation.getArgument(0));

		SensorResponse created = service.create(OWNER, 7, SensorType.PH, " as-900 ", "Lote norte");
		assertThat(created.getSensorCode()).isEqualTo("AS-900");
		assertThat(created.getSensorType()).isEqualTo("PH");
		assertThat(created.getIdCrop()).isEqualTo(7);
		assertThat(created.getActive()).isTrue();

		when(sensors.existsBySensorCode("AS-900")).thenReturn(true);
		assertThatThrownBy(() -> service.create(OWNER, 7, SensorType.PH, "AS-900", null))
				.isInstanceOf(BusinessRuleException.class);

		assertThatThrownBy(() -> service.create("other@agrosense.test", 7, SensorType.PH, "AS-901", null))
				.isInstanceOf(ResourceNotFoundException.class);
		assertThatThrownBy(() -> service.findByCrop("other@agrosense.test", 7))
				.isInstanceOf(ResourceNotFoundException.class);
		verify(sensors, times(1)).save(any(Sensor.class));

		when(sensors.findByCropIdCropOrderBySensorCodeAsc(7)).thenReturn(List.of(
				Sensor.builder().idSensor(1).sensorCode("AS-900").sensorType(SensorType.PH).crop(crop).build()));
		assertThat(service.findByCrop(OWNER, 7)).extracting(SensorResponse::getSensorCode).containsExactly("AS-900");
	}

	private static Crop crop(int id) {
		Crop crop = new Crop();
		crop.setIdCrop(id);
		return crop;
	}
}
