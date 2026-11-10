package com.agrosense.backend.pattern.structural.adapter.esp32;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The connection to the ESP32's TCP server: one line per reading, ending in '\n'. It runs on a thread of its own, so
 * the backend starts and keeps working whether or not the ESP32 is there. A dropped connection, or one silent for
 * the read timeout, is opened again after a wait that doubles from {@code firstWait} to {@code longestWait}.
 */
@Slf4j
public class Esp32TcpClient {

    private final String host;
    private final int port;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final Duration firstWait;
    private final Duration longestWait;
    private final Consumer<String> onLine;

    private final ExecutorService thread = Executors.newSingleThreadExecutor(runnable -> {
        Thread worker = new Thread(runnable, "esp32-tcp");
        worker.setDaemon(true);
        return worker;
    });
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final AtomicInteger connections = new AtomicInteger();
    private volatile Socket socket;
    private volatile boolean connected;

    public Esp32TcpClient(String host, int port, Duration connectTimeout, Duration readTimeout, Duration firstWait,
            Duration longestWait, Consumer<String> onLine) {
        this.host = host;
        this.port = port;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.firstWait = firstWait;
        this.longestWait = longestWait;
        this.onLine = onLine;
    }

    public void start() {
        if (running.compareAndSet(false, true)) {
            thread.submit(this::run);
        }
    }

    public void stop() {
        running.set(false);
        stopped.countDown();
        closeQuietly(socket);
        thread.shutdownNow();
        try {
            thread.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public boolean connected() {
        return connected;
    }

    /** How many times a connection was opened: more than one means it reconnected. */
    public int connections() {
        return connections.get();
    }

    private void run() {
        Duration wait = firstWait;
        while (running.get()) {
            try (Socket opened = new Socket()) {
                socket = opened;
                opened.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
                opened.setSoTimeout((int) readTimeout.toMillis());
                connected = true;
                connections.incrementAndGet();
                log.info("ESP32: connected to {}:{}", host, port);
                wait = firstWait;
                readLines(opened.getInputStream());
                log.warn("ESP32: {}:{} closed the connection", host, port);
            } catch (SocketTimeoutException silent) {
                log.warn("ESP32: nothing from {}:{} in {} s, reconnecting", host, port, readTimeout.toSeconds());
            } catch (IOException unreachable) {
                if (running.get()) {
                    log.warn("ESP32: {}:{} unreachable ({}), retrying in {} s", host, port,
                            unreachable.getMessage(), wait.toSeconds());
                }
            } finally {
                connected = false;
                socket = null;
            }
            if (!running.get() || pause(wait)) {
                return;
            }
            wait = wait.multipliedBy(2).compareTo(longestWait) > 0 ? longestWait : wait.multipliedBy(2);
        }
    }

    /** Lines longer than the adapter accepts are skipped whole instead of being kept in memory. */
    private void readLines(InputStream input) throws IOException {
        Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        StringBuilder line = new StringBuilder();
        boolean tooLong = false;
        int c;
        while (running.get() && (c = reader.read()) != -1) {
            if (c == '\n') {
                if (!tooLong) {
                    deliver(line.toString());
                }
                line.setLength(0);
                tooLong = false;
            } else if (c != '\r' && !tooLong) {
                line.append((char) c);
                if (line.length() > Esp32ReadingAdapter.MAX_LINE) {
                    log.warn("ESP32: line longer than {} characters skipped", Esp32ReadingAdapter.MAX_LINE);
                    line.setLength(0);
                    tooLong = true;
                }
            }
        }
    }

    private void deliver(String line) {
        try {
            onLine.accept(line);
        } catch (RuntimeException failure) {
            log.warn("ESP32: a line could not be handled: {}", failure.getMessage());
        }
    }

    /** True when stop() was called during the wait. */
    private boolean pause(Duration wait) {
        try {
            return stopped.await(wait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return true;
        }
    }

    private static void closeQuietly(Socket open) {
        if (open == null) {
            return;
        }
        try {
            open.close();
        } catch (IOException ignored) {
            // closing is all that was wanted
        }
    }
}
