package com.agrosense.backend.pattern.structural.adapter;

import io.netty.channel.ChannelOption;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Optional;

/** Turns Open-Meteo's parallel arrays (daily.time, daily.temperature_2m_min) into a {@link ColdestDay}. */
@Slf4j
@Component
public class OpenMeteoAdapter implements WeatherProvider {

    private static final int FORECAST_DAYS = 2;

    private final WebClient client;

    public OpenMeteoAdapter(@Value("${weather.base-url:https://api.open-meteo.com}") String baseUrl,
            @Value("${weather.timeout-ms:8000}") int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        this.client = WebClient.builder().baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }

    @Override
    public Optional<ColdestDay> coldestDayAhead(BigDecimal latitude, BigDecimal longitude) {
        try {
            JsonNode forecast = client.get()
                    .uri(uri -> uri.path("/v1/forecast")
                            .queryParam("latitude", latitude.toPlainString())
                            .queryParam("longitude", longitude.toPlainString())
                            .queryParam("daily", "temperature_2m_min")
                            .queryParam("forecast_days", FORECAST_DAYS)
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve().bodyToMono(JsonNode.class).block();
            return forecast == null ? Optional.empty() : coldestIn(forecast.path("daily"));
        } catch (RuntimeException failure) {
            log.warn("Could not read the weather forecast: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<ColdestDay> coldestIn(JsonNode daily) {
        ColdestDay coldest = null;
        JsonNode dates = daily.path("time");
        JsonNode minimums = daily.path("temperature_2m_min");
        for (int day = 0; day < Math.min(dates.size(), minimums.size()); day++) {
            if (!minimums.get(day).isNumber()) {
                continue;
            }
            BigDecimal minimum = minimums.get(day).decimalValue();
            if (coldest == null || minimum.compareTo(coldest.minimumTemperature()) < 0) {
                try {
                    coldest = new ColdestDay(LocalDate.parse(dates.get(day).asString("")), minimum);
                } catch (DateTimeException badDate) {
                    log.warn("Open-Meteo sent a day that is not a date");
                }
            }
        }
        return Optional.ofNullable(coldest);
    }
}
