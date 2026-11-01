package com.agrosense.backend.pattern.creational.prototype;

import com.agrosense.backend.models.Crop;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** A crop's starting ranges. The catalogue keeps the originals; a new crop always works on a deep copy. */
@Getter
public final class CropTemplate implements Cloneable {

    @Getter
    @Setter
    @AllArgsConstructor
    public static final class Range {
        private BigDecimal min;
        private BigDecimal max;

        Range copy() {
            return new Range(min, max);
        }
    }

    private final String key;
    private final String label;
    private Range humidity;
    private Range temperature;
    private Range ph;

    public CropTemplate(String key, String label, Range humidity, Range temperature, Range ph) {
        this.key = key;
        this.label = label;
        this.humidity = humidity;
        this.temperature = temperature;
        this.ph = ph;
    }

    @Override
    public CropTemplate clone() {
        try {
            CropTemplate copy = (CropTemplate) super.clone();
            copy.humidity = humidity.copy();
            copy.temperature = temperature.copy();
            copy.ph = ph.copy();
            return copy;
        } catch (CloneNotSupportedException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public void applyTo(Crop crop) {
        crop.setHumidityMin(humidity.getMin());
        crop.setHumidityMax(humidity.getMax());
        crop.setTempMin(temperature.getMin());
        crop.setTempMax(temperature.getMax());
        crop.setPhMin(ph.getMin());
        crop.setPhMax(ph.getMax());
    }
}
