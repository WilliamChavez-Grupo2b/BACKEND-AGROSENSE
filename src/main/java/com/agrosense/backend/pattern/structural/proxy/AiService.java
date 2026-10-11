package com.agrosense.backend.pattern.structural.proxy;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

/** What the application asks of the AI service. Implemented by the real client and by its proxy. */
public interface AiService {

    /** The fields of an irrigation prediction that the application stores. */
    record IrrigationPrediction(String recommendation, BigDecimal confidence, String modelUsed) {
    }

    /** @return the prediction, or empty when the service answered without one */
    Optional<IrrigationPrediction> predictIrrigation(Integer cropId, Map<String, Object> sensorData);
}
