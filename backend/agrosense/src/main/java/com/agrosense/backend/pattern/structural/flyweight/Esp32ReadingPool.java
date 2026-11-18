package com.agrosense.backend.pattern.structural.flyweight;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Flyweight factory: the ESP32 writes a block every second and its values barely move (the DHT11 reads whole
 * percents), so most blocks of a minute are equal. The pool gives one shared, immutable Esp32Reading per set of
 * values: the aggregation window keeps 60 references to a handful of objects instead of 60 objects.
 *
 * <p>The reading is the intrinsic state, shared; the moment each block arrived is the extrinsic state, which the feed
 * receives apart (Esp32Feed.blockReceived(block, at)). Only the {@value #CAPACITY} values used last are kept.
 */
public final class Esp32ReadingPool {

    public static final int CAPACITY = 256;

    private final Map<Esp32Reading, Esp32Reading> pool = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Esp32Reading, Esp32Reading> eldest) {
            return size() > CAPACITY;
        }
    };
    private long requests;
    private long shared;

    /** The shared reading with these values, created the first time they are seen. */
    public synchronized Esp32Reading of(Double airTemperature, Double relativeHumidity, Double soilMoisture) {
        requests++;
        Esp32Reading wanted = new Esp32Reading(airTemperature, relativeHumidity, soilMoisture);
        Esp32Reading kept = pool.putIfAbsent(wanted, wanted);
        if (kept != null) {
            shared++;
            return kept;
        }
        return wanted;
    }

    public synchronized int size() {
        return pool.size();
    }

    /** How many requests got a reading that already existed. */
    public synchronized long shared() {
        return shared;
    }

    public synchronized long requests() {
        return requests;
    }
}
