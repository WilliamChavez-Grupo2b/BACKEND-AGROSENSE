package com.agrosense.backend.pattern.estructural.facade;

import com.agrosense.backend.domain.models.*;
import com.agrosense.backend.dto.response.DashboardResponse;
import com.agrosense.backend.dto.response.LectureResponse;
import com.agrosense.backend.pattern.creacional.builder.AlertBuilder;
import com.agrosense.backend.pattern.creacional.factory.SensorFactory;
import com.agrosense.backend.pattern.estructural.adapter.LectureInt;
import com.agrosense.backend.pattern.estructural.adapter.MensajeMqtt;
import com.agrosense.backend.pattern.estructural.adapter.MqttAdapter;
import com.agrosense.backend.pattern.estructural.decorator.NormalizationDecorator;
import com.agrosense.backend.repository.*;
import com.agrosense.backend.websocket.SensorWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * PATRÓN FACADE
 * Proporciona una interfaz unificada y simple
 * para el subsistema de sensores complejo.
 * Los controladores solo llaman a esta fachada
 * sin necesidad de conocer los detalles internos:
 * Adapter + Decorator + Builder + Repository + WebSocket.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensorFacade {

    private final SensorRepository       sensorRepo;
    private final LectureRepository      lectureRepo;
    private final AlertRepository       alertRepo;
    private final CultiveRepository      cultiveRepo;
    private final MqttAdapter            mqttAdapter;
    private final NormalizationDecorator normalizator;
    private final AlertBuilder          alertBuilder;
    private final SensorWebSocketHandler wsHandler;

    /**
     * Operación principal: recibe un message MQTT del ESP32,
     * lo adapta, valida, normaliza, guarda y notifica.
     * Un solo método que orquesta todo el subsistema.
     */
    @Transactional
    public LectureSensor procesarMensajeMqtt(MensajeMqtt message) {

        // 1. Adapter: convertir formato externo → interno
        LectureInt interned = mqttAdapter.adapter(message);

        // 2. Buscar sensor por código
        Sensor sensor = sensorRepo
            .findBySensorcode(interna.getSensorcode())
            .orElseThrow(() -> new RuntimeException(
                "Sensor no registrado: " + interna.getSensorcode()));

        // 3. Construir la lecture
        LectureSensor lecture = LectureSensor.builder()
            .sensor(sensor)
            .value(interna.getValue())
            .unity(interna.getUnity())
            .quality(interna.getQuality())
            .time(LocalDateTime.now())
            .build();

        // 4. Decorator: validar y normalizar
        LectureSensor lectureNormalized = normalizator.procesar(lecture);

        // 5. Guardar en base de datos
        lectureRepo.save(lectureNormalized);

        // 6. Actualizar última lecture del sensor
        sensor.setLastLecture(LocalDateTime.now());
        sensorRepo.save(sensor);

        // 7. Verificar umbrales y generar alerts
        verifiedUmbral(sensor, lectureNormalized.getValue());

        // 8. WebSocket: notificar al frontend en time real
        wsHandler.broadcast(lectureNormalized);

        log.info("Lecture proces: sensor={} value={}{}",
            sensor.getSensorcode(),
            lectureNormalized.getValue(),
            lectureNormalized.getUnity());

        return lectureNormalized;
    }

    /**
     * Verifica umbrales usando el AlertBuilder.
     */
    private void verifiedUmbral(Sensor sensor, BigDecimal value) {
        Cultive cultive = sensor.getCultive();
        if (cultive == null) return;

        switch (sensor.getTipoSensor()) {
            case HUMEDAD_SUELO -> {
                if (value.compareTo(cultive.getHumedadMin()) < 0) {
                    Alert alert = alertBuilder
                        .paraCultive(cultive)
                        .conSensor(sensor)
                        .deTipo(com.agrosense.backend.domain.enums.TipoAlert.HUMEDAD_BAJA)
                        .conMensaje(String.format(
                            "Humedad del suelo en %.1f%% — mínimo: %.1f%%",
                            value, cultive.getHumedadMin()))
                        .conValue(value)
                        .calcularSeveridadAutomatica()
                        .build();
                    alertRepo.save(alert);
                    wsHandler.broadcastAlert(alert);
                }
            }
            case TEMPERATURA_AIRE -> {
                if (value.compareTo(cultive.getTempMax()) > 0) {
                    Alert alert = alertBuilder
                        .paraCultive(cultive)
                        .conSensor(sensor)
                        .deTipo(com.agrosense.backend.domain.enums.TipoAlert.TEMPERATURA_ALTA)
                        .conMensaje(String.format(
                            "Temperatura en %.1f°C — máximo: %.1f°C",
                            value, cultive.getTempMax()))
                        .conValue(value)
                        .calcularSeveridadAutomatica()
                        .build();
                    alertRepo.save(alert);
                    wsHandler.broadcastAlert(alert);
                }
            }
            case PH -> {
                if (value.compareTo(cultive.getPhMin()) < 0
                        || value.compareTo(cultive.getPhMax()) > 0) {
                    Alert alert = alertBuilder
                        .paraCultive(cultive)
                        .conSensor(sensor)
                        .deTipo(com.agrosense.backend.domain.enums.TipoAlert.PH_FUERA_RANGO)
                        .conMensaje(String.format(
                            "pH en %.2f — rango ideal: [%.1f - %.1f]",
                            value, cultive.getPhMin(), cultive.getPhMax()))
                        .conValue(value)
                        .calcularSeveridadAutomatica()
                        .build();
                    alertRepo.save(alert);
                    wsHandler.broadcastAlert(alert);
                }
            }
            default -> { /* otros sensores sin umbrales aún */ }
        }
    }

    /**
     * Dashboard simplificado para el controlador.
     */
    public DashboardResponse obtenerDashboard(Integer idCultive) {
        List<LectureSensor> ultimas = lectureRepo
            .findUltimasPorCultive(idCultive,
                PageRequest.of(0, 20));

        List<Alert> alerts = alertRepo
            .findByCultive_IdCultiveAndAtendidaFalse(idCultive);

        BigDecimal humedad = extraerUltimo(ultimas, "HUMEDAD_SUELO");
        BigDecimal temp    = extraerUltimo(ultimas, "TEMPERATURA_AIRE");
        BigDecimal ph      = extraerUltimo(ultimas, "PH");

        return DashboardResponse.builder()
            .totalCultives(cultiveRepo.findByActivoTrue().size())
            .totalSensores(sensorRepo
                .findByCultive_IdCultiveAndActivoTrue(idCultive).size())
            .alertsPendientes(alerts.size())
            .ultimaHumedad(humedad)
            .ultimaTemperatura(temp)
            .ultimoPh(ph)
            .alertsActivas(alerts.stream()
                .map(this::toAlertResponse)
                .collect(Collectors.toList()))
            .build();
    }

    private BigDecimal extraerUltimo(List<LectureSensor> lectures,
                                     String tipo) {
        return lectures.stream()
            .filter(l -> l.getSensor()
                          .getTipoSensor().name().equals(tipo))
            .map(LectureSensor::getValue)
            .findFirst()
            .orElse(null);
    }

    private com.agrosense.backend.dto.response.AlertResponse
            toAlertResponse(Alert a) {
        return com.agrosense.backend.dto.response.AlertResponse.builder()
            .idAlert(a.getIdAlert())
            .tipoAlert(a.getTipoAlert().name())
            .severidad(a.getSeveridad().name())
            .message(a.getMensaje())
            .valueDetectado(a.getValueDetectado())
            .atendida(a.getAtendida())
            .idCultive(a.getCultive().getIdCultive())
            .creadoEn(a.getCreadoEn())
            .build();
    }
}