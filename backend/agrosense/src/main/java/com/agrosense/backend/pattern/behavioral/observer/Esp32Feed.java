package com.agrosense.backend.pattern.behavioral.observer;

import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32Reading;
import com.agrosense.backend.pattern.structural.adapter.esp32.Esp32ReadingAggregator.Window;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Observer, the subject: the ESP32's feed. Esp32TcpIngestionService tells it what happened and it tells every
 * observer subscribed. An observer that fails is logged and the others are still told.
 */
@Slf4j
public class Esp32Feed {

    private final List<Esp32FeedObserver> observers = new CopyOnWriteArrayList<>();

    public void subscribe(Esp32FeedObserver observer) {
        observers.add(observer);
    }

    public void unsubscribe(Esp32FeedObserver observer) {
        observers.remove(observer);
    }

    public void connectionOpened(Instant at) {
        notifyAll(observer -> observer.connectionOpened(at));
    }

    public void blockReceived(Esp32Reading block, Instant at) {
        notifyAll(observer -> observer.blockReceived(block, at));
    }

    public void windowStored(Window window) {
        notifyAll(observer -> observer.windowStored(window));
    }

    public void windowRefused(Window window, String reason) {
        notifyAll(observer -> observer.windowRefused(window, reason));
    }

    public void connectionLost(Instant at) {
        notifyAll(observer -> observer.connectionLost(at));
    }

    private void notifyAll(Consumer<Esp32FeedObserver> event) {
        for (Esp32FeedObserver observer : observers) {
            try {
                event.accept(observer);
            } catch (RuntimeException failure) {
                log.warn("ESP32: an observer of the feed failed: {}", failure.getMessage());
            }
        }
    }
}
