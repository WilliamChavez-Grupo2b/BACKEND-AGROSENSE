package com.agrosense.backend.ai.service;

import com.agrosense.backend.ai.service.FarmSnapshot.CropStatus;
import com.agrosense.backend.ai.service.FarmSnapshot.IrrigationCycle;
import com.agrosense.backend.ai.service.FarmSnapshot.OpenAlert;
import com.agrosense.backend.ai.service.FarmSnapshot.Receiver;
import com.agrosense.backend.ai.service.FarmSnapshot.SensorSource;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Device;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReceiverStatus;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32TcpIngestionService;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.DeviceRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import com.agrosense.backend.service.AlertService;
import com.agrosense.backend.service.WateringRuleService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What the assistant knows about the user's farm: each crop with its newest readings and ranges, the alerts and
 * irrigations, and where every figure comes from (sensor, crop, place, device, time) and where it is kept.
 */
@Service
public class FarmContextService {

    public static final int MAX_CROPS = 8;
    public static final int MAX_ALERTS = 5;
    public static final int MAX_ATTENDED_ALERTS = 3;
    public static final int MAX_IRRIGATIONS = 5;
    public static final int MAX_SOURCES = 16;

    /** What the field device (the ESP32) measures: soil moisture, air temperature and air humidity. */
    static final Set<SensorType> DEVICE_MEASURES = EnumSet.of(SensorType.SOIL_MOISTURE, SensorType.AIR_TEMPERATURE,
            SensorType.RELATIVE_HUMIDITY);

    private final CropRepository cropRepository;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final IrrigationRepository irrigationRepository;
    private final DeviceRepository deviceRepository;
    private final Supplier<Receiver> receiver;
    private final Supplier<String> wateringRule;

    @Autowired
    public FarmContextService(CropRepository cropRepository, SensorRepository sensorRepository,
            SensorReadingRepository readingRepository, AlertRepository alertRepository, AlertService alertService,
            IrrigationRepository irrigationRepository, DeviceRepository deviceRepository,
            ObjectProvider<Esp32TcpIngestionService> esp32, ObjectProvider<WateringRuleService> wateringRule) {
        this(cropRepository, sensorRepository, readingRepository, alertRepository, alertService, irrigationRepository,
                deviceRepository,
                () -> {
                    Esp32TcpIngestionService service = esp32.getIfAvailable();
                    return service == null ? null : receiver(service.status());
                },
                () -> {
                    WateringRuleService service = wateringRule.getIfAvailable();
                    return service == null ? null : service.rule().orElse(null);
                });
    }

    /** Without the sources: for the tests that only look at crops, alerts and irrigations. */
    public FarmContextService(CropRepository cropRepository, SensorRepository sensorRepository,
            SensorReadingRepository readingRepository, AlertRepository alertRepository, AlertService alertService,
            IrrigationRepository irrigationRepository) {
        this(cropRepository, sensorRepository, readingRepository, alertRepository, alertService, irrigationRepository,
                null, () -> null, () -> null);
    }

    public FarmContextService(CropRepository cropRepository, SensorRepository sensorRepository,
            SensorReadingRepository readingRepository, AlertRepository alertRepository, AlertService alertService,
            IrrigationRepository irrigationRepository, DeviceRepository deviceRepository, Supplier<Receiver> receiver,
            Supplier<String> wateringRule) {
        this.cropRepository = cropRepository;
        this.sensorRepository = sensorRepository;
        this.readingRepository = readingRepository;
        this.alertRepository = alertRepository;
        this.alertService = alertService;
        this.irrigationRepository = irrigationRepository;
        this.deviceRepository = deviceRepository;
        this.receiver = receiver;
        this.wateringRule = wateringRule;
    }

    @Transactional(readOnly = true)
    public FarmSnapshot snapshot(String email) {
        List<Crop> crops = cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(email);
        List<CropStatus> statuses = crops.stream().limit(MAX_CROPS).map(this::status).toList();
        List<OpenAlert> alerts = alertService.findOpen(email, MAX_ALERTS).stream()
                .map(alert -> new OpenAlert(alert.getCropName(), alert.getMessage(), alert.getAlertType(),
                        alert.getSeverity(), alert.getDetectedValue(), alert.getCreatedAt()))
                .toList();
        List<OpenAlert> attended = alertRepository
                .findByCropEstateUserEmailAndAcknowledgedTrueOrderByCreatedAtDesc(email,
                        PageRequest.of(0, MAX_ATTENDED_ALERTS))
                .stream()
                .map(alert -> new OpenAlert(alert.getCrop().getName(), alert.getMessage(),
                        alert.getAlertType() == null ? null : alert.getAlertType().name(),
                        alert.getSeverity() == null ? null : alert.getSeverity().name(),
                        alert.getDetectedValue(), alert.getCreatedAt()))
                .toList();
        List<IrrigationCycle> irrigations = irrigationRepository
                .findByCropEstateUserEmailOrderByStartedAtDesc(email, PageRequest.of(0, MAX_IRRIGATIONS))
                .stream()
                .map(cycle -> new IrrigationCycle(cycle.getCrop().getName(), cycle.getStartedAt(),
                        cycle.getDurationMin(), cycle.getWaterLiters(),
                        cycle.getType() == null ? null : cycle.getType().name(), cycle.getReason()))
                .toList();
        return new FarmSnapshot(crops.size(),
                (int) sensorRepository.countByCropEstateUserEmailAndActiveTrue(email),
                (int) alertRepository.countByCropEstateUserEmailAndAcknowledgedFalse(email),
                statuses, alerts, attended, irrigations, sources(email), receiver.get(), wateringRule.get());
    }

    /** Each active sensor with the device of its crop and its newest reading: where every figure comes from. */
    private List<SensorSource> sources(String email) {
        if (deviceRepository == null) {
            return List.of();
        }
        Map<Integer, String> devices = deviceRepository.findByCropEstateUserEmailAndActiveTrue(email).stream()
                .collect(Collectors.groupingBy(device -> device.getCrop().getIdCrop(),
                        Collectors.mapping(Device::getDeviceCode, Collectors.joining(", "))));
        return sensorRepository
                .findByCropEstateUserEmailAndActiveTrueAndCropActiveTrueOrderByCropNameAscSensorTypeAsc(email)
                .stream().limit(MAX_SOURCES).map(sensor -> source(sensor, devices)).toList();
    }

    private SensorSource source(Sensor sensor, Map<Integer, String> devices) {
        SensorReading newest = readingRepository
                .findBySensorIdSensorOrderByRecordedAtDesc(sensor.getIdSensor(), PageRequest.of(0, 1))
                .stream().findFirst().orElse(null);
        // The ESP32 measures only these three; a pH or conductivity sensor of the same crop is sample data.
        String device = DEVICE_MEASURES.contains(sensor.getSensorType()) ? devices.get(sensor.getCrop().getIdCrop())
                : null;
        return new SensorSource(sensor.getCrop().getName(),
                sensor.getSensorType() == null ? null : sensor.getSensorType().name(), sensor.getLocation(), device,
                newest == null ? null : newest.getValue(), newest == null ? null : newest.getRecordedAt());
    }

    static Receiver receiver(Esp32ReceiverStatus status) {
        return new Receiver(status.enabled(), status.deviceId(), status.linkLabel(), status.aggregation(),
                status.aggregateSeconds(), status.lastStoredAt() == null ? null
                        : status.lastStoredAt().atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
    }

    private CropStatus status(Crop crop) {
        Estate estate = crop.getEstate();
        return new CropStatus(crop.getName(),
                latest(crop, SensorType.SOIL_MOISTURE), crop.getHumidityMin(), crop.getHumidityMax(),
                latest(crop, SensorType.AIR_TEMPERATURE), crop.getTempMin(), crop.getTempMax(),
                latest(crop, SensorType.PH), crop.getPhMin(), crop.getPhMax(),
                crop.getVariety(), crop.getStage() == null ? null : crop.getStage().name(), crop.getSowingDate(),
                estate == null ? null : estate.getName(), estate == null ? null : estate.getLocation());
    }

    private BigDecimal latest(Crop crop, SensorType type) {
        return readingRepository
                .findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(crop.getIdCrop(), type,
                        ValidationDecorator.QUALITY_OK)
                .map(SensorReading::getValue)
                .orElse(null);
    }
}
