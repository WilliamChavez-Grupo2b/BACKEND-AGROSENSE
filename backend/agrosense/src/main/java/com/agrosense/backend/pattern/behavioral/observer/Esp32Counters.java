package com.agrosense.backend.pattern.behavioral.observer;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator.Window;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/** Observer: counts what the feed received, stored and refused, for GET /api/devices/esp32/status. */
public class Esp32Counters implements Esp32FeedObserver {

    private final AtomicLong receivedBlocks = new AtomicLong();
    private final AtomicLong storedReadings = new AtomicLong();
    private final AtomicLong refusedReadings = new AtomicLong();
    private volatile Instant lastBlockAt;
    private volatile Instant lastStoredAt;

    @Override
    public void blockReceived(Esp32Reading block, Instant at) {
        receivedBlocks.incrementAndGet();
        lastBlockAt = at;
    }

    @Override
    public void windowStored(Window window) {
        storedReadings.incrementAndGet();
        lastStoredAt = window.endsAt();
    }

    @Override
    public void windowRefused(Window window, String reason) {
        refusedReadings.incrementAndGet();
    }

    public long receivedBlocks() {
        return receivedBlocks.get();
    }

    public long storedReadings() {
        return storedReadings.get();
    }

    public long refusedReadings() {
        return refusedReadings.get();
    }

    public Instant lastBlockAt() {
        return lastBlockAt;
    }

    public Instant lastStoredAt() {
        return lastStoredAt;
    }
}
