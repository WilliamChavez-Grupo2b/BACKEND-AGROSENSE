package com.agrosense.backend.pattern.structural.proxy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

public interface AiService {

    record IrrigationPrediction(String recommendation, BigDecimal confidence, String modelUsed, boolean irrigate,
            Integer durationMinutes) {

        public IrrigationPrediction(String recommendation, BigDecimal confidence, String modelUsed) {
            this(recommendation, confidence, modelUsed, false, null);
        }
    }

    Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData);
}
