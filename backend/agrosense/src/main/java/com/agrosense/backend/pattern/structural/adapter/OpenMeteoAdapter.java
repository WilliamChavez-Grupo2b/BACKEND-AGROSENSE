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
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Turns Open-Meteo's parallel arrays, one entry per day, into an {@link Outlook}. Keeps it for an hour per place. */
@Slf4j
@Component
public class OpenMeteoAdapter implements WeatherProvider {

    private static final int FORECAST_DAYS = 2;
    private static final Duration KEEP_FOR = Duration.ofHours(1);
    private static final int MAX_PLACES = 256;

    private record Kept(Outlook outlook, Instant until) {
    }

    private final WebClient client;
    private final Map<String, Kept> kept = new ConcurrentHashMap<>();

    public OpenMeteoAdapter(@Value("${weather.base-url:https://api.open-meteo.com}") String baseUrl,
            @Value("${weather.timeout-ms:8000}") int timeoutMs) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, timeoutMs)
                .responseTimeout(Duration.ofMillis(timeoutMs));
        this.client = WebClient.builder().baseUrl(baseUrl)
                .clientConnector(new ReactorClientHttpConnector(httpClient)).build();
    }

    @Override
    public Optional<Outlook> outlook(BigDecimal latitude, BigDecimal longitude) {
        Instant now = Instant.now();
        String place = latitude.toPlainString() + "," + longitude.toPlainString();
        Kept known = kept.get(place);
        if (known != null && now.isBefore(known.until())) {
            return Optional.of(known.outlook());
        }
        Optional<Outlook> fresh = fetch(latitude, longitude);
        fresh.ifPresent(outlook -> {
            kept.values().removeIf(entry -> !now.isBefore(entry.until()));
            if (kept.size() < MAX_PLACES) {
                kept.put(place, new Kept(outlook, now.plus(KEEP_FOR)));
            }
        });
        return fresh;
    }

    private Optional<Outlook> fetch(BigDecimal latitude, BigDecimal longitude) {
        try {
            JsonNode forecast = client.get()
                    .uri(uri -> uri.path("/v1/forecast")
                            .queryParam("latitude", latitude.toPlainString())
                            .queryParam("longitude", longitude.toPlainString())
                            .queryParam("daily", "temperature_2m_min,precipitation_probability_max,precipitation_sum")
                            .queryParam("forecast_days", FORECAST_DAYS)
                            .queryParam("timezone", "auto")
                            .build())
                    .retrieve().bodyToMono(JsonNode.class).block();
            return forecast == null ? Optional.empty() : outlookIn(forecast.path("daily"));
        } catch (RuntimeException failure) {
            log.warn("Could not read the weather forecast: {}", failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private static Optional<Outlook> outlookIn(JsonNode daily) {
        JsonNode dates = daily.path("time");
        JsonNode minimums = daily.path("temperature_2m_min");
        JsonNode chances = daily.path("precipitation_probability_max");
        JsonNode rain = daily.path("precipitation_sum");
        LocalDate coldestDay = null;
        BigDecimal coldest = null;
        Integer chance = null;
        BigDecimal rainMm = null;
        for (int day = 0; day < Math.min(dates.size(), minimums.size()); day++) {
            if (chances.path(day).isNumber()) {
                chance = chance == null ? chances.path(day).asInt() : Math.max(chance, chances.path(day).asInt());
            }
            if (rain.path(day).isNumber()) {
                rainMm = (rainMm == null ? BigDecimal.ZERO : rainMm).add(rain.path(day).decimalValue());
            }
            if (minimums.get(day).isNumber()
                    && (coldest == null || minimums.get(day).decimalValue().compareTo(coldest) < 0)) {
                try {
                    coldestDay = LocalDate.parse(dates.get(day).asString(""));
                    coldest = minimums.get(day).decimalValue();
                } catch (DateTimeException badDate) {
                    log.warn("Open-Meteo sent a day that is not a date");
                }
            }
        }
        return coldest == null ? Optional.empty() : Optional.of(new Outlook(coldestDay, coldest, chance, rainMm));
    }
}
