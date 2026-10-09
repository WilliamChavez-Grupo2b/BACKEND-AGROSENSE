package com.agrosense.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

@Data
@Builder
public class DashboardResponse {

    private Integer totalCrops;
    private Integer totalSensors;
    private Integer pendingAlerts;
    private BigDecimal lastHumidity;
    private BigDecimal lastTemperature;
    private BigDecimal lastPh;
    private String aiRecommendation;
    private List<SensorReadingResponse> latestReadings;
    private List<AlertResponse> activeAlerts;
}
