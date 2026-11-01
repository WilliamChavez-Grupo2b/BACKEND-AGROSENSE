package com.agrosense.backend.service;

import com.agrosense.backend.dto.request.CropRequest;
import com.agrosense.backend.dto.request.EstateRequest;
import com.agrosense.backend.dto.response.CropResponse;
import com.agrosense.backend.dto.response.EstateResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Estate;
import com.agrosense.backend.models.User;
import com.agrosense.backend.pattern.creational.prototype.CropTemplateCatalog;
import com.agrosense.backend.repository.CropRepository;
import com.agrosense.backend.repository.EstateRepository;
import com.agrosense.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EstateCropService {

    private final EstateRepository estateRepository;
    private final CropRepository cropRepository;
    private final UserRepository userRepository;
    private final CropTemplateCatalog templates;

    @Transactional(readOnly = true)
    public List<EstateResponse> findEstates(String email) {
        return estateRepository.findByUserEmailOrderByNameAsc(email).stream()
                .map(EstateResponse::from)
                .toList();
    }

    @Transactional
    public EstateResponse createEstate(String email, EstateRequest request) {
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

    @Transactional(readOnly = true)
    public List<CropResponse> findActiveCrops(String email, Integer estateId) {
        List<Crop> crops = estateId == null
                ? cropRepository.findByEstateUserEmailAndActiveTrueOrderByNameAsc(email)
                : cropRepository.findByEstateIdEstateAndActiveTrueOrderByNameAsc(
                        ownedEstate(email, estateId).getIdEstate());
        return crops.stream().map(CropResponse::from).toList();
    }

    @Transactional
    public CropResponse createCrop(String email, CropRequest request) {
        Crop crop = Crop.builder()
                .estate(ownedEstate(email, request.getEstateId()))
                .name(request.getName().trim())
                .variety(request.getVariety() == null || request.getVariety().isBlank()
                        ? null : request.getVariety().trim())
                .sowingDate(request.getSowingDate())
                .build();
        if (request.getTemplate() != null && !request.getTemplate().isBlank()) {
            templates.cloneOf(request.getTemplate()).applyTo(crop);
        }
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

    private Estate ownedEstate(String email, Integer estateId) {
        return estateRepository.findByIdEstateAndUserEmail(estateId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Estate not found"));
    }
}
