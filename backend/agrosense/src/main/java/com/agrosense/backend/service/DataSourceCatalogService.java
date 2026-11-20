package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.ReadingSource;
import com.agrosense.backend.dto.response.DataSourcesResponse;
import com.agrosense.backend.dto.response.DataSourcesResponse.Entry;
import com.agrosense.backend.repository.SensorReadingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The traceability catalogue served by GET /api/data-sources. */
@Service
public class DataSourceCatalogService {

    private static final String JAVA = "backend/agrosense/src/main/java/com/agrosense/backend/";

    static final List<Entry> CATALOGUE = List.of(
            new Entry("readings.esp32", "Lecturas del ESP32 (promedio por ventana)", "SENSOR_FISICO",
                    "com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService",
                    "sensor_readings.reading_value (source = ESP32_TCP)", "GET /api/sensors/{sensorId}/readings",
                    JAVA + "pattern/structural/adapter/esp32/Esp32TcpIngestionService.java"),
            new Entry("readings.device", "Lecturas de dispositivos (evento o MQTT)", "SENSOR_FISICO",
                    "com.agrosense.backend.pattern.structural.facade.DeviceIngestionFacade",
                    "sensor_readings.reading_value (source = DEVICE)", "POST /api/internal/events",
                    JAVA + "pattern/structural/facade/DeviceIngestionFacade.java"),
            new Entry("readings.simulated", "Lecturas del sensor virtual", "SENSOR_VIRTUAL",
                    "com.agrosense.backend.service.VirtualSensorSimulator",
                    "sensor_readings.reading_value (source = SIMULATED)", "GET /api/sensors/{sensorId}/readings",
                    JAVA + "service/VirtualSensorSimulator.java"),
            new Entry("readings.manual", "Lecturas registradas a mano", "ENDPOINT",
                    "com.agrosense.backend.service.SensorService",
                    "sensor_readings.reading_value (source = MANUAL)", "POST /api/readings",
                    JAVA + "service/SensorService.java"),
            new Entry("readings.seed", "Lecturas de ejemplo", "SEED", "database/seed_demo.sql",
                    "sensor_readings.reading_value (source = SEED)", "GET /api/sensors/{sensorId}/readings",
                    "database/seed_demo.sql"),
            new Entry("alerts", "Alertas", "CALCULO",
                    "com.agrosense.backend.pattern.structural.facade.SensorFacade",
                    "alerts.alert_type", "GET /api/alerts",
                    JAVA + "pattern/structural/facade/SensorFacade.java"),
            new Entry("predictions", "Recomendaciones de riego de la IA", "IA",
                    "com.agrosense.backend.service.PredictionService",
                    "ai_predictions.recommendation", "GET /api/crops/{cropId}/predictions",
                    JAVA + "service/PredictionService.java"),
            new Entry("irrigations", "Riegos", "ENDPOINT",
                    "com.agrosense.backend.service.IrrigationService",
                    "irrigations.duration_min", "GET /api/riego/cultivo/{cropId}",
                    JAVA + "service/IrrigationService.java"),
            new Entry("dashboard", "Indicadores del panel", "CALCULO",
                    "com.agrosense.backend.service.DashboardService",
                    null, "GET /api/dashboard",
                    JAVA + "service/DashboardService.java"),
            new Entry("chat", "Asistente (chat de IA)", "IA",
                    "com.agrosense.backend.ai.service.AiAssistantService",
                    null, "POST /api/ai/chat",
                    JAVA + "ai/service/AiAssistantService.java"));

    private final SensorReadingRepository readingRepository;
    private final VirtualSensorSimulator simulator;

    public DataSourceCatalogService(SensorReadingRepository readingRepository, VirtualSensorSimulator simulator) {
        this.readingRepository = readingRepository;
        this.simulator = simulator;
    }

    @Transactional(readOnly = true)
    public DataSourcesResponse describe(String email) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (ReadingSource source : ReadingSource.values()) {
            counts.put(source.name(), 0L);
        }
        for (Object[] row : readingRepository.countBySourceSince(email, LocalDateTime.now().minusHours(24))) {
            counts.put(((ReadingSource) row[0]).name(), ((Number) row[1]).longValue());
        }
        return new DataSourcesResponse(CATALOGUE, counts, simulator.enabled());
    }
}
