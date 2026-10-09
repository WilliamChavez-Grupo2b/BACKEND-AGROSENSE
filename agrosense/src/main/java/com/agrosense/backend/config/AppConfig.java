package com.agrosense.backend.config;

import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AppConfig {

    /** Name of the pool that runs {@code @Async} methods; reference it as {@code @Async(ASYNC_EXECUTOR)}. */
    public static final String ASYNC_EXECUTOR = "asyncExecutor";

    /** Loads the AI settings into the singleton once, at startup, and exposes it for injection. */
    @Bean
    public AiConfigManager aiConfigManager(
            @Value("${ai.service.url:}") String serviceUrl,
            @Value("${ai.service.timeout-ms:5000}") int timeoutMs,
            @Value("${ai.service.max-attempts:1}") int maxAttempts) {
        AiConfigManager manager = AiConfigManager.getInstance();
        manager.configure(serviceUrl, timeoutMs, !serviceUrl.isBlank(), maxAttempts);
        return manager;
    }

    @Bean(ASYNC_EXECUTOR)
    public ThreadPoolTaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agrosense-async-");
        // When the pool and its queue are full the caller runs the task itself, so none is dropped.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
