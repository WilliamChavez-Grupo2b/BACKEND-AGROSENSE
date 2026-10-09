package com.agrosense.backend.service;

import com.agrosense.backend.domain.enums.IrrigationType;
import com.agrosense.backend.dto.request.RiegoRequest;
import com.agrosense.backend.dto.response.IrrigationResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Irrigation;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.IrrigationRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class RiegoService {

    private static final int MAX_IRRIGATIONS = 200;

    private final IrrigationRepository irrigationRepository;
    private final CropRepository cropRepository;
    private final UserRepository userRepository;

    /** Registers a manual irrigation cycle that starts now on one of the user's crops. */
    @Transactional
    public IrrigationResponse register(String email, RiegoRequest request) {
        Crop crop = ownedCrop(email, request.getCropId());
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        LocalDateTime startedAt = LocalDateTime.now();
        Irrigation irrigation = irrigationRepository.save(Irrigation.builder()
                .crop(crop)
                .startedAt(startedAt)
                .endedAt(startedAt.plusMinutes(request.getDurationMinutes()))
                .durationMin(request.getDurationMinutes())
                .waterLiters(request.getWaterLiters())
                .type(IrrigationType.MANUAL)
                .reason(request.getReason().trim())
                .activatedBy(user)
                .build());
        log.info("Riego manual registrado para el cultivo {}: {} L en {} min",
                crop.getIdCrop(), irrigation.getWaterLiters(), irrigation.getDurationMin());
        return IrrigationResponse.from(irrigation);
    }

    /** The irrigation cycles of one of the user's crops, newest first. */
    @Transactional(readOnly = true)
    public List<IrrigationResponse> findHistory(String email, Integer cropId, int limit) {
        ownedCrop(email, cropId);
        int size = Math.min(Math.max(limit, 1), MAX_IRRIGATIONS);
        return irrigationRepository.findByCropIdCropOrderByStartedAtDesc(cropId, PageRequest.of(0, size)).stream()
                .map(IrrigationResponse::from)
                .toList();
    }

    /** Another user's crop is reported as missing, so its existence is not revealed. */
    private Crop ownedCrop(String email, Integer cropId) {
        return cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
    }
}
