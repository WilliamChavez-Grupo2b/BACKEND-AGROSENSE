package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.Alert;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AlertResponse {

    private Integer idAlert;
    private String alertType;
    private String severity;
    private String message;
    private BigDecimal detectedValue;
    private Boolean acknowledged;
    private Integer idCrop;
    private String cropName;
    private LocalDateTime createdAt;

    public static AlertResponse from(Alert alert) {
        return AlertResponse.builder()
                .idAlert(alert.getIdAlert())
                .alertType(alert.getAlertType().name())
                .severity(alert.getSeverity().name())
                .message(alert.getMessage())
                .detectedValue(alert.getDetectedValue())
                .acknowledged(alert.getAcknowledged())
                .idCrop(alert.getCrop().getIdCrop())
                .cropName(alert.getCrop().getName())
                .createdAt(alert.getCreatedAt())
                .build();
    }
}
