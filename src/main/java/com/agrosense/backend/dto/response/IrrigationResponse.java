package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.Irrigation;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class IrrigationResponse {

    private Integer idIrrigation;
    private Integer idCrop;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Integer durationMin;
    private BigDecimal waterLiters;
    private String type;
    private String reason;

    public static IrrigationResponse from(Irrigation irrigation) {
        return IrrigationResponse.builder()
                .idIrrigation(irrigation.getIdIrrigation())
                .idCrop(irrigation.getCrop().getIdCrop())
                .startedAt(irrigation.getStartedAt())
                .endedAt(irrigation.getEndedAt())
                .durationMin(irrigation.getDurationMin())
                .waterLiters(irrigation.getWaterLiters())
                .type(irrigation.getType().name())
                .reason(irrigation.getReason())
                .build();
    }
}
