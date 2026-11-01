package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.IrrigationRequest;
import com.agrosense.backend.dto.response.IrrigationResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.structural.bridge.ActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.AiIrrigationOrder;
import com.agrosense.backend.pattern.structural.bridge.ManualIrrigationOrder;
import com.agrosense.backend.pattern.structural.proxy.AiService.IrrigationPrediction;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
public class IrrigationService {

    private static final int MAX_IRRIGATIONS = 200;

    private final IrrigationRepository irrigationRepository;
    private final CropRepository cropRepository;
    private final UserRepository userRepository;
    private final ActuatorDriver actuator;
    private final boolean aiOrders;

    public IrrigationService(IrrigationRepository irrigationRepository, CropRepository cropRepository,
            UserRepository userRepository, ActuatorDriver actuator,
            @Value("${irrigation.ai-orders:false}") boolean aiOrders) {
        this.irrigationRepository = irrigationRepository;
        this.cropRepository = cropRepository;
        this.userRepository = userRepository;
        this.actuator = actuator;
        this.aiOrders = aiOrders;
    }

    @Transactional
    public IrrigationResponse register(String email, IrrigationRequest request) {
        Crop crop = ownedCrop(email, request.getCropId());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        Irrigation irrigation = new ManualIrrigationOrder(actuator, crop, user, request.getDurationMinutes(),
                request.getWaterLiters(), request.getReason().trim())
                .execute(irrigationRepository::save).orElseThrow();
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
                .execute(irrigationRepository::save)
                .ifPresent(irrigation -> log.info("AI irrigation ordered for crop {}: {} min", cropId,
                        irrigation.getDurationMin()));
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
