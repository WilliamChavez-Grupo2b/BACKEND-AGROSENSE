package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.SensorReadingRequest;
import com.agrosense.backend.dto.response.SensorReadingResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.pattern.structural.adapter.SensorReadingData;
import com.agrosense.backend.pattern.structural.facade.SensorFacade;
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
public class SensorReadingService {

    private static final int MAX_READINGS = 500;

    private final SensorFacade sensorFacade;
    private final SensorRepository sensorRepository;
    private final SensorReadingRepository readingRepository;

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
}
