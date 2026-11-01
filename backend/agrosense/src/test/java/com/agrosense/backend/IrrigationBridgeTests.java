package com.agrosense.backend;

import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.dto.request.DeviceEventRequest;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.pattern.structural.bridge.ActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.AiIrrigationOrder;
import com.agrosense.backend.pattern.structural.bridge.IrrigationCommand;
import com.agrosense.backend.pattern.structural.bridge.IrrigationOrder;
import com.agrosense.backend.pattern.structural.bridge.ManualIrrigationOrder;
import com.agrosense.backend.pattern.structural.bridge.PendingIrrigationCommands;
import com.agrosense.backend.pattern.structural.bridge.RelayActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.SimulatedActuatorDriver;
import com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade;
import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.pattern.structural.proxy.AiServiceProxy;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.UserRepository;
import com.agrosense.backend.service.DeviceEventService;
import com.agrosense.backend.service.IrrigationService;
import com.agrosense.backend.service.PredictionService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IrrigationBridgeTests {

	private final Crop crop = Crop.builder().idCrop(1).name("Café").build();
	private final UnaryOperator<Irrigation> stored = irrigation -> irrigation;
	private final List<IrrigationCommand> started = new ArrayList<>();
	private final ActuatorDriver recording = started::add;

	private static IrrigationPrediction prediction(boolean irrigate, String confidence, Integer minutes, String model) {
		return new IrrigationPrediction("Regar", confidence == null ? null : new BigDecimal(confidence), model,
				irrigate, minutes);
	}

	private IrrigationOrder manual(ActuatorDriver driver) {
		return new ManualIrrigationOrder(driver, crop, new User(), 20, new BigDecimal("150"), "Suelo seco");
	}

	private IrrigationOrder ai(ActuatorDriver driver) {
		return new AiIrrigationOrder(driver, crop, prediction(true, "0.90", 15, "m1"));
	}

	@Test
	void everyOrderWorksWithEveryDriver() {
		for (IrrigationOrder order : List.of(manual(new SimulatedActuatorDriver()), ai(new SimulatedActuatorDriver()))) {
			assertThat(order.execute(stored)).isPresent();
		}

		PendingIrrigationCommands pending = new PendingIrrigationCommands();
		manual(new RelayActuatorDriver(pending)).execute(stored);
		assertThat(pending.take(1).orElseThrow().source()).isEqualTo(IrrigationCommand.MANUAL);
		ai(new RelayActuatorDriver(pending)).execute(stored);
		IrrigationCommand command = pending.take(1).orElseThrow();
		assertThat(command.source()).isEqualTo(IrrigationCommand.AI);
		assertThat(command.minutes()).isEqualTo(15);
		assertThat(command.confidence()).isEqualByComparingTo("0.90");
	}

	@Test
	void eachOrderRecordsItsOwnKindOfIrrigation() {
		Irrigation byHand = manual(recording).execute(stored).orElseThrow();
		assertThat(byHand.getType()).isEqualTo(IrrigationType.MANUAL);
		assertThat(byHand.getWaterLiters()).isEqualByComparingTo("150");
		assertThat(byHand.getActivatedBy()).isNotNull();

		Irrigation byModel = ai(recording).execute(stored).orElseThrow();
		assertThat(byModel.getType()).isEqualTo(IrrigationType.AUTOMATIC);
		assertThat(byModel.getActivatedBy()).isNull();
		assertThat(byModel.getReason()).contains("m1");
		assertThat(started).hasSize(2);
	}

	@Test
	void aPredictionHasToEarnTheWater() {
		List<IrrigationPrediction> refused = List.of(
				prediction(false, "0.90", 15, "m1"),
				prediction(true, "0.69", 15, "m1"),
				prediction(true, null, 15, "m1"),
				prediction(true, "0.90", null, "m1"),
				prediction(true, "0.90", 0, "m1"),
				prediction(true, "0.90", AiIrrigationOrder.MAX_MINUTES + 1, "m1"),
				prediction(true, "0.90", 15, AiServiceProxy.LOCAL_MODEL));
		for (IrrigationPrediction prediction : refused) {
			assertThat(new AiIrrigationOrder(recording, crop, prediction).execute(stored)).isEmpty();
		}
		assertThat(started).isEmpty();
	}

	@Test
	void theWaterStartsOnlyOnceTheIrrigationIsStored() {
		List<String> steps = new ArrayList<>();
		manual(command -> steps.add("driver")).execute(irrigation -> {
			steps.add("stored");
			return irrigation;
		});
		assertThat(steps).containsExactly("stored", "driver");
	}

	@Test
	void anOrderWaitsOnceAndExpires() {
		Instant start = Instant.parse("2026-11-01T12:00:00Z");
		Clock[] clock = {Clock.fixed(start, ZoneOffset.UTC)};
		PendingIrrigationCommands pending = new PendingIrrigationCommands(new Clock() {
			@Override
			public Instant instant() {
				return clock[0].instant();
			}

			@Override
			public java.time.ZoneId getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}
		});
		IrrigationCommand command = new IrrigationCommand(1, 5, IrrigationCommand.MANUAL, null);

		pending.offer(command);
		assertThat(pending.take(2)).isEmpty();
		assertThat(pending.take(1)).contains(command);
		assertThat(pending.take(1)).isEmpty();

		pending.offer(command);
		clock[0] = Clock.fixed(start.plus(PendingIrrigationCommands.EXPIRES_AFTER), ZoneOffset.UTC);
		assertThat(pending.take(1)).isEmpty();
	}

	@Test
	void anAiOrderNeedsTheSettingOnTheService() {
		IrrigationRepository irrigations = mock(IrrigationRepository.class);
		CropRepository crops = mock(CropRepository.class);
		when(crops.getReferenceById(1)).thenReturn(crop);
		when(irrigations.save(any())).thenAnswer(call -> call.getArgument(0));
		IrrigationPrediction confident = prediction(true, "0.90", 15, "m1");

		new IrrigationService(irrigations, crops, mock(UserRepository.class), recording, false)
				.orderFromPrediction(1, confident);
		verify(irrigations, never()).save(any());
		assertThat(started).isEmpty();

		new IrrigationService(irrigations, crops, mock(UserRepository.class), recording, true)
				.orderFromPrediction(1, confident);
		assertThat(started).hasSize(1);
	}

	@Test
	void theFacadeHandsTheOrderToTheDeviceInItsAnswer() {
		DeviceEventService events = mock(DeviceEventService.class);
		when(events.receive(any(), any(), any(), any(), any())).thenAnswer(call -> {
			Map<String, Object> answer = new LinkedHashMap<>();
			answer.put("ownerEmail", "demo@agrosense.co");
			answer.put("event", Map.of("cropId", 1, "readings", List.of()));
			return answer;
		});
		PendingIrrigationCommands pending = new PendingIrrigationCommands();
		pending.offer(new IrrigationCommand(1, 7, IrrigationCommand.AI, new BigDecimal("0.8")));
		DeviceIngestionFacade facade = new DeviceIngestionFacade(events, mock(PredictionService.class),
				mock(AiConfigManager.class), pending, Duration.ZERO, Clock.systemUTC());

		Map<String, Object> first = facade.ingest("key", new DeviceEventRequest());
		assertThat(first.get("command")).isEqualTo(Map.of("type", "irrigate", "source", "AI", "minutes", 7,
				"confidence", new BigDecimal("0.8")));
		assertThat(facade.ingest("key", new DeviceEventRequest())).doesNotContainKey("command");
	}
}
