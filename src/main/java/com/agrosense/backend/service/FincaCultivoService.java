package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.CultivoRequest;
import com.agrosense.backend.dto.request.FincaRequest;
import com.agrosense.backend.dto.response.CropResponse;
import com.agrosense.backend.dto.response.EstateResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.User;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Estates and their crops. Every operation is limited to what belongs to the authenticated user. */
@Service
@RequiredArgsConstructor
public class FincaCultivoService {

    private final EstateRepository estateRepository;
    private final CropRepository cropRepository;
    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public List<EstateResponse> findEstates(String email) {
        return estateRepository.findByUserEmailOrderByNameAsc(email).stream()
                .map(EstateResponse::from)
                .toList();
    }

    @Transactional
    public EstateResponse createEstate(String email, FincaRequest request) {
        User owner = userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
        return EstateResponse.from(estateRepository.save(Estate.builder()
                .user(owner)
                .name(request.getName().trim())
                .location(request.getLocation().trim())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .areaHa(request.getAreaHa())
                .build()));
    }

    /**
     * The user's active crops, ordered by name.
     *
     * @param estateId limits the list to one estate; {@code null} lists the crops of every estate
     */
    @Transactional(readOnly = true)
    public List<CropResponse> findActiveCrops(String email, Integer estateId) {
        List<Crop> crops = estateId == null
                ? cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(email)
                : cropRepository.findByEstateIdEstateAndActiveTrueOrderByNameAsc(
                        ownedEstate(email, estateId).getIdEstate());
        return crops.stream().map(CropResponse::from).toList();
    }

    @Transactional
    public CropResponse createCrop(String email, CultivoRequest request) {
        Crop crop = Crop.builder()
                .estate(ownedEstate(email, request.getEstateId()))
                .name(request.getName().trim())
                .variety(request.getVariety() == null || request.getVariety().isBlank()
                        ? null : request.getVariety().trim())
                .sowingDate(request.getSowingDate())
                .build();
        // A range left out keeps the entity's default; the request guarantees both ends come together.
        if (request.getHumidityMin() != null) {
            crop.setHumidityMin(request.getHumidityMin());
            crop.setHumidityMax(request.getHumidityMax());
        }
        if (request.getTempMin() != null) {
            crop.setTempMin(request.getTempMin());
            crop.setTempMax(request.getTempMax());
        }
        if (request.getPhMin() != null) {
            crop.setPhMin(request.getPhMin());
            crop.setPhMax(request.getPhMax());
        }
        return CropResponse.from(cropRepository.save(crop));
    }

    /** Another user's estate is reported as missing, so its existence is not revealed. */
    private Estate ownedEstate(String email, Integer estateId) {
        return estateRepository.findByIdEstateAndUserEmail(estateId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Estate not found"));
    }
}
