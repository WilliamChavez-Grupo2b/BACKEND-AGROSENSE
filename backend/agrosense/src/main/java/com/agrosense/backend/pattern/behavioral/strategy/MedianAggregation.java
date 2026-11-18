package com.agrosense.backend.pattern.behavioral.strategy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** The median of the window: one wild block (a spike of the sensor) does not move it. */
public class MedianAggregation implements AggregationStrategy {

    @Override
    public Double combine(Iterator<Double> values) {
        List<Double> sorted = new ArrayList<>();
        values.forEachRemaining(sorted::add);
        if (sorted.isEmpty()) {
            return null;
        }
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        double median = sorted.size() % 2 == 1 ? sorted.get(middle) : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        return AggregationStrategy.round(median);
    }

    @Override
    public String name() {
        return "median";
    }
}
