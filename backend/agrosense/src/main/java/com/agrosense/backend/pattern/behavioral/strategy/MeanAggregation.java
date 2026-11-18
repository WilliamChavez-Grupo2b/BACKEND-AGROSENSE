package com.agrosense.backend.pattern.behavioral.strategy;

import java.util.Iterator;

/** The arithmetic mean of the window: what the ESP32 measured on average over the minute. */
public class MeanAggregation implements AggregationStrategy {

    @Override
    public Double combine(Iterator<Double> values) {
        double sum = 0;
        int count = 0;
        while (values.hasNext()) {
            sum += values.next();
            count++;
        }
        return count == 0 ? null : AggregationStrategy.round(sum / count);
    }

    @Override
    public String name() {
        return "mean";
    }
}
