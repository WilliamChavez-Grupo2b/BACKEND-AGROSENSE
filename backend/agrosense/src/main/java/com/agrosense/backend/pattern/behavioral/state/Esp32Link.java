package com.agrosense.backend.pattern.behavioral.state;

import com.agrosense.backend.pattern.behavioral.observer.Esp32FeedObserver;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * State, the context: the link with the ESP32. It observes the feed and hands each event to its current state, which
 * returns the next one. The ESP32 writes a block every second, so one silent for {@code silentAfter} stopped sending.
 */
public class Esp32Link implements Esp32FeedObserver {

    private final Clock clock;
    private final Duration silentAfter;
    private Esp32LinkState state;
    private Instant lastBlockAt;

    /** A receiver that is off has no link yet: it starts disconnected. */
    public Esp32Link(Clock clock, Duration silentAfter) {
        this.clock = clock;
        this.silentAfter = silentAfter;
        this.state = Esp32LinkState.DISCONNECTED;
    }

    @Override
    public synchronized void connectionOpened(Instant at) {
        state = state.connectionOpened();
    }

    @Override
    public synchronized void blockReceived(Esp32Reading block, Instant at) {
        lastBlockAt = at;
        state = state.blockReceived();
    }

    @Override
    public synchronized void connectionLost(Instant at) {
        state = state.connectionLost();
    }

    /** The state now, once the time since the last block has been taken into account. */
    public synchronized Esp32LinkState state() {
        state = state.tick(sinceLastBlock(), silentAfter);
        return state;
    }

    public synchronized String label() {
        return state().label(sinceLastBlock());
    }

    private Duration sinceLastBlock() {
        return lastBlockAt == null ? null : Duration.between(lastBlockAt, clock.instant());
    }
}
