package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.dto.request.SensorReadingRequest;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.dto.response.SensorResponse;
import com.agrosense.backend.exception.BusinessRuleException;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;
import com.agrosense.backend.pattern.creational.factory.SensorFactory;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.SensorReadingRepository;
import com.agrosense.backend.repository.SensorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SensorService {

    private static final int MAX_READINGS = 500;

    private final SensorFacade sensorFacade;
    private final SensorFactory sensorFactory;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;
    private final CropRepository cropRepository;

    /** Records a reading sent over REST. The sensor must belong to the authenticated user. */
    @Transactional
    public SensorReadingResponse record(String email, SensorReadingRequest request) {
        String sensorCode = request.getSensorCode().trim().toUpperCase(Locale.ROOT);
        if (!sensorRepository.existsBySensorCodeAndCropEstateUserEmail(sensorCode, email)) {
            throw new ResourceNotFoundException("Sensor not found");
        }
        return SensorReadingResponse.from(sensorFacade.recordReading(SensorReadingData.builder()
                .sensorCode(sensorCode)
                .value(request.getValue())
                .unit(request.getUnit() == null || request.getUnit().isBlank() ? null : request.getUnit().trim())
                .build()));
    }

    /** Most recent readings of one of the user's sensors, newest first. */
    @Transactional(readOnly = true)
    public List<SensorReadingResponse> findLatest(String email, Integer sensorId, int limit) {
        sensorRepository.findByIdSensorAndCropEstateUserEmail(sensorId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Sensor not found"));
        int size = Math.min(Math.max(limit, 1), MAX_READINGS);
        return readingRepository.findBySensorIdSensorOrderByRecordedAtDesc(sensorId, PageRequest.of(0, size))
                .stream()
                .map(SensorReadingResponse::from)
                .toList();
    }

    /** Registers a sensor on one of the user's crops. Sensor codes are unique across the system. */
    @Transactional
    public SensorResponse create(String email, Integer cropId, SensorType type, String sensorCode,
            String location) {
        Sensor sensor = sensorFactory.create(type, sensorCode, location, ownedCrop(email, cropId));
        if (sensorRepository.existsBySensorCode(sensor.getSensorCode())) {
            throw new BusinessRuleException("Ya existe un sensor con ese código.");
        }
        return SensorResponse.from(sensorRepository.save(sensor));
    }

    /** Every sensor of one of the user's crops, ordered by code. */
    @Transactional(readOnly = true)
    public List<SensorResponse> findByCrop(String email, Integer cropId) {
        ownedCrop(email, cropId);
        return sensorRepository.findByCropIdCropOrderBySensorCodeAsc(cropId).stream()
                .map(SensorResponse::from)
                .toList();
    }

    /** Another user's crop is reported as missing, so its existence is not revealed. */
    private Crop ownedCrop(String email, Integer cropId) {
        return cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
    }
}
