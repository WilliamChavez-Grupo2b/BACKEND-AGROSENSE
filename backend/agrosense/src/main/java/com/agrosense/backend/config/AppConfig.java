package com.agrosense.backend.config;

import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import com.agrosense.backend.pattern.structural.bridge.ActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.PendingIrrigationCommands;
import com.agrosense.backend.pattern.structural.bridge.RelayActuatorDriver;
import com.agrosense.backend.pattern.structural.bridge.SimulatedActuatorDriver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AppConfig {

    public static final String ASYNC_EXECUTOR = "asyncExecutor";

    @Bean
    public AiConfigManager aiConfigManager(
            @Value("${ai.service.url:}") String serviceUrl,
            @Value("${ai.service.timeout-ms:30000}") int timeoutMs,
            @Value("${ai.service.max-attempts:1}") int maxAttempts) {
        AiConfigManager manager = AiConfigManager.getInstance();
        manager.configure(serviceUrl, timeoutMs, !serviceUrl.isBlank(), maxAttempts);
        return manager;
    }

    @Bean
    public ActuatorDriver actuatorDriver(@Value("${irrigation.actuator:simulated}") String actuator,
            @Value("${irrigation.max-run-minutes:5}") int maxRunMinutes, PendingIrrigationCommands pending) {
        return "relay".equalsIgnoreCase(actuator.trim()) ? new RelayActuatorDriver(pending, maxRunMinutes)
                : new SimulatedActuatorDriver();
    }

    @Bean(ASYNC_EXECUTOR)
    public ThreadPoolTaskExecutor asyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("agrosense-async-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        return executor;
    }
}
