package com.agrosense.backend.pattern.creational.singleton;

/**
 * Singleton pattern: the one place that holds the AI service settings at runtime. It starts disabled and
 * is configured once at startup from the application properties (see AiClientService).
 */
public final class AiConfigManager {

    /** Initialization-on-demand holder: lazy and thread-safe without explicit locking. */
    private static final class Holder {
        private static final AiConfigManager INSTANCE = new AiConfigManager();
    }

    /** Immutable snapshot, replaced atomically so readers never see half-updated settings. */
    public record Settings(String serviceUrl, int timeoutMs, boolean enabled, int maxAttempts) {
    }

    private volatile Settings settings = new Settings("", 5000, false, 1);

    private AiConfigManager() {
    }

    public static AiConfigManager getInstance() {
        return Holder.INSTANCE;
    }

    public void configure(String serviceUrl, int timeoutMs, boolean enabled, int maxAttempts) {
        if (timeoutMs <= 0 || maxAttempts <= 0) {
            throw new IllegalArgumentException("Timeout and attempts must be positive");
        }
        this.settings = new Settings(serviceUrl == null ? "" : serviceUrl.trim(), timeoutMs, enabled, maxAttempts);
    }

    public Settings getSettings() {
        return settings;
    }

    public String getServiceUrl() {
        return settings.serviceUrl();
    }

    public int getTimeoutMs() {
        return settings.timeoutMs();
    }

    public boolean isEnabled() {
        return settings.enabled();
    }

    public int getMaxAttempts() {
        return settings.maxAttempts();
    }
}
