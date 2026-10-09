package com.agrosense.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class DashboardResponse {
    private Integer           totalCultives;
    private Integer           totalSensors;
    private Integer           alertsPendings;
    private BigDecimal        LastHumidity;
    private BigDecimal        LastTemperature;
    private BigDecimal        LastPh;
    private String            recomendationIA;
    private List<LectureResponse> LastLectures;
    private List<AlertResponse>  alertsActives;
}