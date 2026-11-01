package com.agrosense.backend;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.dto.request.CropRequest;
import com.agrosense.backend.dto.response.CropResponse;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider;
import com.agrosense.backend.pattern.structural.adapter.WeatherProvider.ColdestDay;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.service.EstateCropService;
import com.agrosense.backend.service.FieldWatchService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Against the real schema: its CHECK has to accept the two new alert types. */
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
		"login.max-attempts-per-address=0"
})
@ActiveProfiles("seed")
class FieldWatchIntegrationTests {

	private static final String EMAIL = "demo@agrosense.co";

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
	@MockitoBean
	private WeatherProvider weather;

	private long openOf(AlertType type) {
		return alerts.findAll().stream().filter(alert -> alert.getAlertType() == type).count();
	}

	@Test
	void aDeviceThatStoppedReportingRaisesOneAlert() {
		watch.watchSilentDevices();
		assertThat(openOf(AlertType.SENSOR_OFFLINE)).as("a device that never reported is not silent").isZero();

		Device device = devices.findByDeviceCode("RPI-DEMO-01").orElseThrow();
		device.setLastEventAt(LocalDateTime.now().minusHours(1));
		devices.save(device);

		watch.watchSilentDevices();
		watch.watchSilentDevices();
		assertThat(openOf(AlertType.SENSOR_OFFLINE)).isEqualTo(1);
	}

	@Test
	void aForecastFrostRaisesAnAlertOnTheCropsOfTheEstate() {
		when(weather.coldestDayAhead(any(), any())).thenReturn(
				Optional.of(new ColdestDay(LocalDate.now().plusDays(1), new BigDecimal("0.5"))));

		watch.watchFrost();
		long raised = openOf(AlertType.FROST_RISK);
		watch.watchFrost();

		assertThat(raised).isPositive();
		assertThat(openOf(AlertType.FROST_RISK)).isEqualTo(raised);
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
}
