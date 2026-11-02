package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.request.IrrigationRequest;
import com.agrosense.backend.dto.response.IrrigationResponse;
import com.agrosense.backend.exception.ApiException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.SensorReading;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.structural.bridge.ActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.AiIrrigationOrder;
import com.agrosense.backend.pattern.structural.bridge.ManualIrrigationOrder;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
public class IrrigationService {

    private static final int MAX_IRRIGATIONS = 200;
    private static final int SOAK_MINUTES = 30;
    private static final int GAIN_SAMPLE = 10;

    private final IrrigationRepository irrigationRepository;
    private final CropRepository cropRepository;
    private final UserRepository userRepository;
    private final SensorReadingRepository readingRepository;
    private final ActuatorDriver actuator;
    private final boolean aiOrders;

    public IrrigationService(IrrigationRepository irrigationRepository, CropRepository cropRepository,
            UserRepository userRepository, SensorReadingRepository readingRepository, ActuatorDriver actuator,
            @Value("${irrigation.ai-orders:false}") boolean aiOrders) {
        this.irrigationRepository = irrigationRepository;
        this.cropRepository = cropRepository;
        this.userRepository = userRepository;
        this.readingRepository = readingRepository;
        this.actuator = actuator;
        this.aiOrders = aiOrders;
    }

    @Transactional
    public IrrigationResponse register(String email, IrrigationRequest request) {
        Crop crop = ownedCrop(email, request.getCropId());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        int limit = actuator.maxRunMinutes();
        if (limit > 0 && request.getDurationMinutes() > limit) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La bomba funciona como máximo " + limit
                    + " minutos seguidos. Pide " + limit + " minutos o menos y repite el riego si hace falta.");
        }
        Irrigation irrigation = new ManualIrrigationOrder(actuator, crop, user, request.getDurationMinutes(),
                request.getWaterLiters(), request.getReason().trim())
                .execute(this::storeWithMoisture).orElseThrow();
        log.info("Manual irrigation recorded for crop {}: {} L in {} min",
                crop.getIdCrop(), irrigation.getWaterLiters(), irrigation.getDurationMin());
        return IrrigationResponse.from(irrigation);
    }

    @Transactional
    public void orderFromPrediction(Integer cropId, IrrigationPrediction prediction) {
        if (!aiOrders) {
            return;
        }
        new AiIrrigationOrder(actuator, cropRepository.getReferenceById(cropId), prediction)
                .execute(this::storeWithMoisture)
                .ifPresent(irrigation -> log.info("AI irrigation ordered for crop {}: {} min", cropId,
                        irrigation.getDurationMin()));
    }

    private Irrigation storeWithMoisture(Irrigation irrigation) {
        latestMoisture(irrigation.getCrop().getIdCrop()).map(SensorReading::getValue)
                .ifPresent(irrigation::setMoistureBefore);
        return irrigationRepository.save(irrigation);
    }

    private Optional<SensorReading> latestMoisture(Integer cropId) {
        return readingRepository.findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(cropId,
                SensorType.SOIL_MOISTURE, ValidationDecorator.QUALITY_OK);
    }

    /**
     * Waits for the water to soak in, then notes the moisture that the irrigation left. Only a reading taken after the
     * water counts: with the device silent, the last one is the soil before it, which would teach that watering does
     * nothing. It waits for a newer one.
     */
    @Transactional
    public void recordResults() {
        LocalDateTime limit = LocalDateTime.now().minusMinutes(SOAK_MINUTES);
        for (Irrigation irrigation : irrigationRepository
                .findByMoistureBeforeIsNotNullAndMoistureAfterIsNullAndEndedAtBefore(limit)) {
            latestMoisture(irrigation.getCrop().getIdCrop())
                    .filter(reading -> reading.getRecordedAt().isAfter(irrigation.getEndedAt()))
                    .map(SensorReading::getValue).ifPresent(irrigation::setMoistureAfter);
        }
    }

    /** Points of moisture per minute of water on this crop, from its latest irrigations; null until there are any. */
    @Transactional(readOnly = true)
    public BigDecimal moistureGainPerMinute(Integer cropId) {
        return irrigationRepository
                .findByCropIdCropAndMoistureAfterIsNotNullAndDurationMinIsNotNullOrderByStartedAtDesc(cropId,
                        PageRequest.of(0, GAIN_SAMPLE))
                .stream()
                .map(irrigation -> irrigation.getMoistureAfter().subtract(irrigation.getMoistureBefore())
                        .divide(BigDecimal.valueOf(irrigation.getDurationMin()), 4, RoundingMode.HALF_UP))
                .filter(gain -> gain.signum() > 0)
                .collect(Collectors.collectingAndThen(Collectors.toList(), gains -> gains.isEmpty() ? null
                        : gains.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                                .divide(BigDecimal.valueOf(gains.size()), 4, RoundingMode.HALF_UP)));
    }

    @Transactional(readOnly = true)
    public List<IrrigationResponse> findHistory(String email, Integer cropId, int limit) {
        ownedCrop(email, cropId);
        int size = Math.min(Math.max(limit, 1), MAX_IRRIGATIONS);
        return irrigationRepository.findByCropIdCropOrderByStartedAtDesc(cropId, PageRequest.of(0, size)).stream()
                .map(IrrigationResponse::from)
                .toList();
    }

    private Crop ownedCrop(String email, Integer cropId) {
        return cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
    }
}
