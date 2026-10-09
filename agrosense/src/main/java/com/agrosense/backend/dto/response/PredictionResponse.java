package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.AiPrediction;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class PredictionResponse {

    private Integer idPrediction;
    private Integer idCrop;
    private String type;
    private BigDecimal confidence;
    private String recommendation;
    private String modelUsed;
    private LocalDateTime createdAt;

    public static PredictionResponse from(AiPrediction prediction) {
        return PredictionResponse.builder()
                .idPrediction(prediction.getIdPrediction())
                .idCrop(prediction.getCrop().getIdCrop())
                .type(prediction.getType())
                .confidence(prediction.getConfidence())
                .recommendation(prediction.getRecommendation())
                .modelUsed(prediction.getModelUsed())
                .createdAt(prediction.getCreatedAt())
                .build();
    }
}
