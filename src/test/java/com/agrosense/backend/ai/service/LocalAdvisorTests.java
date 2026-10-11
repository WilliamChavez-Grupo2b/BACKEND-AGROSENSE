package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** What the assistant answers on its own, from the farm's figures, when the model cannot be reached. */
class LocalAdvisorTests {

	private static final CropStatus COFFEE = crop("Café", "22.5000", "33.0", "6.15");
	private static final CropStatus BANANA = crop("Plátano", "64.7400", "19.26", null);
	private static final CropStatus RICE = crop("Arroz", "92", null, "4.8");
	private static final CropStatus POTATO = crop("Papa", null, null, null);
	private static final FarmSnapshot FARM = new FarmSnapshot(4, 6, 2, List.of(RICE, COFFEE, POTATO, BANANA),
			List.of(new OpenAlert("Café", "La humedad está por debajo del mínimo del cultivo (22.5 %)."),
					new OpenAlert("Papa", "Posible presencia de plaga reportada en la parcela.")));

	@Test
	void irrigationIsJudgedCropByCropAgainstItsOwnLimits() {
		String answer = LocalAdvisor.answer("¿Cuándo conviene regar?", FARM);

		assertThat(answer).contains(
				"**Café**: riega. La humedad (22.5 %) está por debajo de su mínimo (40 %). Con 33 °C, hazlo al "
						+ "amanecer o al atardecer.",
				"**Plátano**: no hace falta regar. La humedad (64.74 %) está en su rango (40–80 %).",
				"**Arroz**: no riegues. La humedad (92 %) supera su máximo (80 %); revisa el drenaje.",
				"**Papa**: no tiene lecturas de humedad; no puedo decirte si regar.");
		// A crop with its own limits is judged by them: 65 % is dry for rice grown at 70 to 95 %.
		CropStatus paddy = new CropStatus("Arroz", new BigDecimal("65"), new BigDecimal("70.0"),
				new BigDecimal("95.0"), null, null, null, null, null, null);
		assertThat(LocalAdvisor.answer("riego", new FarmSnapshot(1, 1, 0, List.of(paddy), List.of())))
				.contains("**Arroz**: riega. La humedad (65 %) está por debajo de su mínimo (70 %).");
	}

	@Test
	void aQuestionThatNamesACropIsAnsweredAboutThatCropOnly() {
		assertThat(LocalAdvisor.answer("¿Tengo que regar el CAFE hoy?", FARM))
				.contains("**Café**: riega").doesNotContain("Plátano", "Arroz", "Papa");
		assertThat(LocalAdvisor.answer("¿Cómo van los plátanos?", FARM))
				.isEqualTo("- **Plátano**: humedad 64.74 %, temperatura 19.26 °C.");
		assertThat(LocalAdvisor.answer("papa", FARM)).isEqualTo("- **Papa**: sin lecturas.");
	}

	@Test
	void alertsAreListedWithTheirCropOrReportedAsNone() {
		assertThat(LocalAdvisor.answer("¿Qué hago con mis alertas?", FARM))
				.startsWith("Tienes 2 alertas sin atender:")
				.contains("- **Café**: La humedad está por debajo del mínimo del cultivo (22.5 %).",
						"- **Papa**: Posible presencia de plaga")
				.endsWith("márcalas como atendidas cuando las resuelvas.");
		FarmSnapshot many = new FarmSnapshot(4, 6, 9, FARM.crops(), FARM.alerts());
		assertThat(LocalAdvisor.answer("alertas", many)).startsWith("Tienes 9 alertas sin atender; las más recientes:");
		FarmSnapshot calm = new FarmSnapshot(4, 6, 0, FARM.crops(), List.of());
		assertThat(LocalAdvisor.answer("¿Hay algún problema?", calm)).isEqualTo("No tienes alertas sin atender.");
	}

	@Test
	void temperatureAndPhAreComparedWithEachCropsRange() {
		assertThat(LocalAdvisor.answer("¿Qué temperatura hay?", FARM)).contains(
				"**Café**: 33 °C, en su rango (15–35 °C).",
				"**Plátano**: 19.26 °C, en su rango (15–35 °C).",
				"**Papa**: sin lecturas de temperatura.");
		assertThat(LocalAdvisor.answer("¿Cómo está el pH del suelo?", FARM)).contains(
				"**Arroz**: 4.8, por debajo de su mínimo (5.5).",
				"**Café**: 6.15, en su rango (5.5–7).",
				"**Plátano**: sin lecturas de pH.");
	}

	@Test
	void theOverviewFlagsWhatIsOutOfRangeAndTheSensorsSayWhoIsSilent() {
		assertThat(LocalAdvisor.answer("¿Cómo están mis cultivos hoy?", FARM))
				.startsWith("Tu finca tiene 4 cultivos, 6 sensores y 2 alertas sin atender.")
				.contains("- **Arroz**: humedad 92 % (alto: máximo 80 %), pH 4.8 (bajo: mínimo 5.5).",
						"- **Café**: humedad 22.5 % (bajo: mínimo 40 %), temperatura 33 °C, pH 6.15.",
						"- **Papa**: sin lecturas.");
		assertThat(LocalAdvisor.answer("¿Cuántos sensores tengo?", FARM))
				.isEqualTo("Tienes 6 sensores activos en 4 cultivos. Sin lecturas todavía: Papa.");
		FarmSnapshot large = new FarmSnapshot(11, 20, 0, FARM.crops(), List.of());
		assertThat(LocalAdvisor.answer("resumen", large)).endsWith("Hay 7 cultivos más que no detallo aquí.");
	}

	@Test
	void whatItCannotAnswerItSaysAlongWithWhatItCan() {
		assertThat(LocalAdvisor.answer("Hola", FARM)).startsWith("Hola. Puedo decirte");
		assertThat(LocalAdvisor.answer("Escribe un poema sobre el mar", FARM))
				.startsWith("Ahora mismo solo puedo responder con los datos de tu finca.")
				.contains("**regar**", "**alertas**");
		assertThat(LocalAdvisor.answer("¿Riego?", null)).contains("no puedo leer los datos de tu finca");
		assertThat(LocalAdvisor.answer("¿Riego?", new FarmSnapshot(0, 0, 0, List.of(), List.of())))
				.startsWith("Todavía no tienes cultivos registrados.");
	}

	@Test
	void theFarmIsDescribedToTheModelWithEachCropsReadingsAndLimits() {
		assertThat(FARM.describe()).isEqualTo("""
				Datos de la finca del usuario: 4 cultivos, 6 sensores, 2 alertas sin atender.
				Cultivo Arroz: humedad del suelo 92 % (rango 40–80 %), pH 4.8 (rango 5.5–7).
				Cultivo Café: humedad del suelo 22.5 % (rango 40–80 %), temperatura 33 °C (rango 15–35 °C), pH 6.15 (rango 5.5–7).
				Cultivo Papa: sin lecturas.
				Cultivo Plátano: humedad del suelo 64.74 % (rango 40–80 %), temperatura 19.26 °C (rango 15–35 °C).
				Alerta en Café: La humedad está por debajo del mínimo del cultivo (22.5 %).
				Alerta en Papa: Posible presencia de plaga reportada en la parcela.""");
	}

	/** A crop with the default limits and these latest readings; {@code null} is a measurement never taken. */
	private static CropStatus crop(String name, String humidity, String temperature, String ph) {
		return new CropStatus(name,
				decimal(humidity), new BigDecimal("40.0"), new BigDecimal("80.0"),
				decimal(temperature), new BigDecimal("15.0"), new BigDecimal("35.0"),
				decimal(ph), new BigDecimal("5.5"), new BigDecimal("7.0"));
	}

	private static BigDecimal decimal(String value) {
		return value == null ? null : new BigDecimal(value);
	}
}
