package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.creational.builder.AlertBuilder;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import com.agrosense.backend.pattern.creational.prototype.SensorReadingPrototype;
import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.pattern.structural.adapter.MessageMqtt;
import com.agrosense.backend.pattern.structural.adapter.MqttAdapter;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.decorator.BaseSensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.NormalizationDecorator;
import com.agrosense.backend.pattern.structural.decorator.SensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PatternTests {

	private final Crop crop = Crop.builder().idCrop(1).name("Coffee").build();

	@Test
	void builderDerivesSeverityAndRefusesIncompleteAlerts() {
		Alert alert = AlertBuilder.forCrop(crop)
				.type(AlertType.LOW_HUMIDITY)
				.detectedValue(new BigDecimal("18"))
				.message("Too dry")
				.automaticSeverity()
				.build();
		assertThat(alert.getSeverity()).isEqualTo(Severity.VERY_HIGH);
		assertThat(alert.getAcknowledged()).isFalse();
		assertThat(alert.getCrop()).isSameAs(crop);

		assertThat(severityFor(AlertType.LOW_HUMIDITY, "25")).isEqualTo(Severity.HIGH);
		assertThat(severityFor(AlertType.LOW_HUMIDITY, "35")).isEqualTo(Severity.MEDIUM);
		assertThat(severityFor(AlertType.HIGH_TEMPERATURE, "46")).isEqualTo(Severity.VERY_HIGH);
		assertThat(severityFor(AlertType.PH_OUT_OF_RANGE, "9")).isEqualTo(Severity.HIGH);
		assertThat(severityFor(AlertType.PEST_DETECTED, "0")).isEqualTo(Severity.VERY_HIGH);

		assertThatThrownBy(() -> AlertBuilder.forCrop(crop).message("No type").build())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> AlertBuilder.forCrop(crop).type(AlertType.WATER_STRESS).message(" ").build())
				.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> AlertBuilder.forCrop(null).type(AlertType.WATER_STRESS).message("x").build())
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void factoryNormalisesTheCodeAndKnowsEveryTypesUnit() {
		Sensor sensor = new SensorFactory().create(SensorType.PH, "  as-900 ", " ", crop);
		assertThat(sensor.getSensorCode()).isEqualTo("AS-900");
		assertThat(sensor.getLocation()).isNull();
		assertThat(sensor.getActive()).isTrue();
		assertThat(sensor.getCrop()).isSameAs(crop);

		for (SensorType type : SensorType.values()) {
			assertThat(SensorFactory.defaultUnit(type)).isNotBlank();
		}
		assertThatThrownBy(() -> new SensorFactory().create(SensorType.PH, " ", null, crop))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void prototypeCreatesIndependentReadingsWithTheTypesDefaults() {
		SensorReadingPrototype prototype = new SensorReadingPrototype();
		Sensor sensor = Sensor.builder().sensorCode("AS-1").sensorType(SensorType.AIR_TEMPERATURE).build();

		SensorReading first = prototype.cloneFor(sensor, new BigDecimal("20"));
		SensorReading second = prototype.cloneFor(sensor, new BigDecimal("21"));
		first.setQuality("CHANGED");

		assertThat(first).isNotSameAs(second);
		assertThat(second.getQuality()).isEqualTo("OK");
		assertThat(second.getUnit()).isEqualTo("°C");
		assertThat(second.getSensor()).isSameAs(sensor);
		assertThat(second.getRecordedAt()).isNotNull();
	}

	@Test
	void singletonAlwaysReturnsTheSameConfiguredInstance() {
		AiConfigManager manager = AiConfigManager.getInstance();
		AiConfigManager.Settings previous = manager.getSettings();
		try {
			manager.configure(" http://ai.local ", 3000, true, 2);

			assertThat(AiConfigManager.getInstance()).isSameAs(manager);
			assertThat(manager.getServiceUrl()).isEqualTo("http://ai.local");
			assertThat(manager.getTimeoutMs()).isEqualTo(3000);
			assertThat(manager.isEnabled()).isTrue();
			assertThat(manager.getMaxAttempts()).isEqualTo(2);
			assertThatThrownBy(() -> manager.configure("x", 0, true, 1)).isInstanceOf(IllegalArgumentException.class);
		} finally {
			manager.configure(previous.serviceUrl(), previous.timeoutMs(), previous.enabled(), previous.maxAttempts());
		}
	}

	@Test
	void adapterTranslatesDeviceMessagesAndRejectsUnusableOnes() {
		MqttAdapter adapter = new MqttAdapter();
		long oneHourAgo = System.currentTimeMillis() - 3_600_000;

		SensorReadingData data = adapter.adapt(new MessageMqtt(" as-001 ", 52.3, " % ", "topic", oneHourAgo));
		assertThat(data.getSensorCode()).isEqualTo("AS-001");
		assertThat(data.getValue()).isEqualByComparingTo("52.3");
		assertThat(data.getUnit()).isEqualTo("%");
		assertThat(data.getRecordedAt()).isBetween(LocalDateTime.now().minusMinutes(61), LocalDateTime.now().minusMinutes(59));

		// No timestamp, or one from the future, falls back to the arrival time.
		long tomorrow = System.currentTimeMillis() + 86_400_000;
		assertThat(adapter.adapt(new MessageMqtt("A", 1.0, null, null, null)).getRecordedAt())
				.isBeforeOrEqualTo(LocalDateTime.now());
		assertThat(adapter.adapt(new MessageMqtt("A", 1.0, null, null, tomorrow)).getRecordedAt())
				.isBeforeOrEqualTo(LocalDateTime.now());

		assertThatThrownBy(() -> adapter.adapt(null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> adapter.adapt(new MessageMqtt(" ", 1.0, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> adapter.adapt(new MessageMqtt("A", null, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> adapter.adapt(new MessageMqtt("A", Double.NaN, null, null, null)))
				.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void decoratorsValidateThenNormalise() {
		SensorReadingProcessor chain =
				new NormalizationDecorator(new ValidationDecorator(new BaseSensorReadingProcessor()));
		Sensor moisture = Sensor.builder().sensorCode("AS-1").sensorType(SensorType.SOIL_MOISTURE).build();

		SensorReading valid = chain.process(SensorReading.builder()
				.sensor(moisture).value(new BigDecimal("52.3456")).quality("OK").build());
		assertThat(valid.getValue()).isEqualByComparingTo("52.35");
		assertThat(valid.getUnit()).isEqualTo("%");
		assertThat(valid.getQuality()).isEqualTo("OK");

		SensorReading impossible = chain.process(SensorReading.builder()
				.sensor(moisture).value(new BigDecimal("140")).unit("pct").quality("OK").build());
		assertThat(impossible.getQuality()).isEqualTo(ValidationDecorator.QUALITY_OUT_OF_RANGE);
		assertThat(impossible.getUnit()).isEqualTo("pct");
	}

	private Severity severityFor(AlertType type, String value) {
		return AlertBuilder.forCrop(crop).type(type).detectedValue(new BigDecimal(value)).message("m")
				.automaticSeverity().build().getSeverity();
	}
}
