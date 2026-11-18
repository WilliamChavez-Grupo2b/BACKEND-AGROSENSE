package com.agrosense.backend.pattern.behavioral.observer;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator.Window;

import java.time.Instant;

/**
 * Observer: what happens on the ESP32's feed, for whoever wants to know. Each observer overrides only what it needs;
 * the feed does not know who listens.
 */
public interface Esp32FeedObserver {

    default void connectionOpened(Instant at) {
    }

    default void blockReceived(Esp32Reading block, Instant at) {
    }

    default void windowStored(Window window) {
    }

    default void windowRefused(Window window, String reason) {
    }

    default void connectionLost(Instant at) {
    }
}
