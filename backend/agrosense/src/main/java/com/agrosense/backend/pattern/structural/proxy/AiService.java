package com.agrosense.backend.pattern.structural.proxy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface AiService {

    record IrrigationPrediction(String recommendation, BigDecimal confidence, String modelUsed) {
    }

    Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData);
}
