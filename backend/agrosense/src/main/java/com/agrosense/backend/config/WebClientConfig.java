package com.agrosense.backend.config;

import com.agrosense.backend.pattern.creational.singleton.AiConfigManager;
import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient aiWebClient(AiConfigManager aiConfig, @Value("${ai.service.key:}") String key,
            @Value("${ai.service.token:}") String token) {
        int timeoutMs = aiConfig.getTimeoutMs();
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs))
                .doOnConnected(connection ->
                        connection.addHandlerLast(new ReadTimeoutHandler(timeoutMs, TimeUnit.MILLISECONDS)));
        WebClient.Builder client = WebClient.builder()
                .baseUrl(aiConfig.getServiceUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        if (!key.isBlank()) {
            client.defaultHeader("X-Internal-Key", key.trim());
        }
        if (!token.isBlank()) {
            client.defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.trim());
        }
        return client.build();
    }
}
