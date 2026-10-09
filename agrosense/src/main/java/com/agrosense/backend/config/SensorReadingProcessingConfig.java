package com.agrosense.backend.config;

import com.agrosense.backend.pattern.structural.decorator.BaseSensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.NormalizationDecorator;
import com.agrosense.backend.pattern.structural.decorator.SensorReadingProcessor;
import com.agrosense.backend.pattern.structural.decorator.ValidationDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SensorReadingProcessingConfig {

    /** The processing chain: validation runs first, then normalization wraps its result. */
    @Bean
    public SensorReadingProcessor sensorReadingProcessor() {
        return new NormalizationDecorator(new ValidationDecorator(new BaseSensorReadingProcessor()));
    }
}
