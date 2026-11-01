package com.agrosense.backend.pattern.creational.builder;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The body of POST /predict/irrigation. Only what was measured is sent, and never a half-made range. */
public final class PredictionRequest {

    private static final BigDecimal PH_LIMIT = new BigDecimal("14");

    private final Map<String, Object> body;

    private PredictionRequest(Map<String, Object> body) {
        this.body = Collections.unmodifiableMap(body);
    }

    public Map<String, Object> toBody() {
        return body;
    }

    public static Builder forCrop(Integer cropId) {
        return new Builder(cropId);
    }

    public static PredictionRequest fromConditions(Integer cropId, Map<String, Object> conditions) {
        return forCrop(cropId)
                .soilMoisture(number(conditions.get("soil_moisture")))
                .airTemperature(number(conditions.get("air_temperature")))
                .ph(number(conditions.get("ph")))
                .humidityRange(number(conditions.get("humidity_min")), number(conditions.get("humidity_max")))
                .phRange(number(conditions.get("ph_min")), number(conditions.get("ph_max")))
                .rain(number(conditions.get("rain_probability")), number(conditions.get("rain_mm")))
                .moistureGainPerMinute(number(conditions.get("moisture_gain_per_minute")))
                .moistureHistory(conditions.get("moisture_history") instanceof List<?> history ? history : null)
                .build();
    }

    private static BigDecimal number(Object value) {
        return value == null ? null : new BigDecimal(value.toString());
    }

    public static final class Builder {

        private final Integer cropId;
        private BigDecimal soilMoisture;
        private BigDecimal airTemperature;
        private BigDecimal ph;
        private BigDecimal humidityMin;
        private BigDecimal humidityMax;
        private BigDecimal phMin;
        private BigDecimal phMax;
        private BigDecimal rainProbability;
        private BigDecimal rainMm;
        private BigDecimal moistureGain;
        private List<?> moistureHistory;

        private Builder(Integer cropId) {
            this.cropId = cropId;
        }

        public Builder soilMoisture(BigDecimal value) {
            this.soilMoisture = value;
            return this;
        }

        public Builder airTemperature(BigDecimal value) {
            this.airTemperature = value;
            return this;
        }

        public Builder ph(BigDecimal value) {
            this.ph = value;
            return this;
        }

        public Builder humidityRange(BigDecimal min, BigDecimal max) {
            this.humidityMin = min;
            this.humidityMax = max;
            return this;
        }

        public Builder phRange(BigDecimal min, BigDecimal max) {
            this.phMin = min;
            this.phMax = max;
            return this;
        }

        public Builder rain(BigDecimal probability, BigDecimal millimetres) {
            this.rainProbability = probability;
            this.rainMm = millimetres;
            return this;
        }

        public Builder moistureGainPerMinute(BigDecimal value) {
            this.moistureGain = value;
            return this;
        }

        public Builder moistureHistory(List<?> history) {
            this.moistureHistory = history;
            return this;
        }

        public PredictionRequest build() {
            if (cropId == null) {
                throw new IllegalStateException("A prediction needs a crop");
            }
            if (soilMoisture == null) {
                throw new IllegalStateException("A prediction needs the soil moisture");
            }
            if (ph != null && (ph.signum() < 0 || ph.compareTo(PH_LIMIT) > 0)) {
                throw new IllegalStateException("The pH must be between 0 and 14");
            }
            requireRange(humidityMin, humidityMax, "humidity");
            requireRange(phMin, phMax, "pH");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("id_crop", cropId);
            body.put("soil_moisture", soilMoisture);
            putIfMeasured(body, "air_temperature", airTemperature);
            putIfMeasured(body, "ph", ph);
            putIfMeasured(body, "humidity_min", humidityMin);
            putIfMeasured(body, "humidity_max", humidityMax);
            putIfMeasured(body, "ph_min", phMin);
            putIfMeasured(body, "ph_max", phMax);
            putIfMeasured(body, "rain_probability", rainProbability);
            putIfMeasured(body, "rain_mm", rainMm);
            putIfMeasured(body, "moisture_gain_per_minute", moistureGain);
            if (moistureHistory != null && !moistureHistory.isEmpty()) {
                body.put("moisture_history", moistureHistory);
            }
            return new PredictionRequest(body);
        }

        private static void requireRange(BigDecimal min, BigDecimal max, String name) {
            if ((min == null) != (max == null) || (min != null && min.compareTo(max) >= 0)) {
                throw new IllegalStateException("The " + name + " range needs a minimum below its maximum");
            }
        }

        private static void putIfMeasured(Map<String, Object> body, String key, BigDecimal value) {
            if (value != null) {
                body.put(key, value);
            }
        }
    }
}
