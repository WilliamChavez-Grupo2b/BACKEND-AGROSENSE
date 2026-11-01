package com.agrosense.backend.pattern.creational.factory.alert;

import com.agrosense.backend.models.Crop;
import com.agrosense.backend.models.Sensor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** What a creator may look at: a stored reading, a weather forecast or a device that went silent. */
public record AlertContext(Crop crop, Sensor sensor, BigDecimal value, String unit, LocalDate forecastDate,
        String deviceCode, LocalDateTime lastSeenAt) {

    public static AlertContext ofReading(Sensor sensor, BigDecimal value, String unit) {
        return new AlertContext(sensor.getCrop(), sensor, value, unit, null, null, null);
    }

    public static AlertContext ofForecast(Crop crop, BigDecimal minimumTemperature, LocalDate date) {
        return new AlertContext(crop, null, minimumTemperature, "°C", date, null, null);
    }

    public static AlertContext ofSilentDevice(Crop crop, String deviceCode, LocalDateTime lastSeenAt) {
        return new AlertContext(crop, null, null, null, null, deviceCode, lastSeenAt);
    }

    public String measured() {
        return value.toPlainString() + " " + unit;
    }
}
