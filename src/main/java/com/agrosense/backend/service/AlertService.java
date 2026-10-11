package com.agrosense.backend.service;

import com.agrosense.backend.dto.response.AlertResponse;
import com.agrosense.backend.exception.ResourceNotFoundException;
import com.agrosense.backend.models.Alert;
import com.agrosense.backend.repository.AlertRepository;
import com.agrosense.backend.repository.CropRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AlertService {

    private static final int MAX_ALERTS = 100;

    private final AlertRepository alertRepository;
    private final CropRepository cropRepository;

    /** The unacknowledged alerts of one of the user's crops, newest first. */
    @Transactional(readOnly = true)
    public List<AlertResponse> findOpenByCrop(String email, Integer cropId, int limit) {
        cropRepository.findByIdCropAndEstateUserEmail(cropId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Crop not found"));
        int size = Math.min(Math.max(limit, 1), MAX_ALERTS);
        return alertRepository
                .findByCropIdCropAndAcknowledgedFalseOrderByCreatedAtDesc(cropId, PageRequest.of(0, size))
                .stream()
                .map(AlertResponse::from)
                .toList();
    }

    /** The user's unacknowledged alerts, newest first. */
    @Transactional(readOnly = true)
    public List<AlertResponse> findOpen(String email, int limit) {
        int size = Math.min(Math.max(limit, 1), MAX_ALERTS);
        return alertRepository
                .findByCropEstateUserEmailAndAcknowledgedFalseOrderByCreatedAtDesc(email, PageRequest.of(0, size))
                .stream()
                .map(AlertResponse::from)
                .toList();
    }

    @Transactional
    public AlertResponse acknowledge(String email, Integer alertId) {
        Alert alert = alertRepository.findByIdAlertAndCropEstateUserEmail(alertId, email)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found"));
        alert.setAcknowledged(true);
        return AlertResponse.from(alert);
    }
}
