package com.agrosense.backend.service;

import lombok.RequieredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
@Slf4j
@Service
@RequiredArgsConstructor
public class IaClientService {
    private final WebClient.Builder webClientBuilder;

    @Value=//Enlace de la IA en la Nube 
    private String iaServiceUrl;

    @Async
    public void askpetition(Integer idcrop, Map<String, Object> DateSensor){
        try {
            webClient client = webClientBuilder
            .baseURL(iaServiceUrl)
            .build();

            //Post para agrosense
            //URL 
            Map<?, ?> result = client.post()
            .uri=//predecir riego 
            .bodyValue(Map.of(
                "id_crop", idcrop,
                "soil moisture",   Datesensor.getOrDefault("humidy", 60),
                "air temperature", Datesensor.getOrDefault("TEMPERATURE", 25)
                "PH",              Datesensor.getOrDefault("ph", 6.5)
            ))
            .retrieve()
            .bodyToMono(Map.class)
            .block();

            if (resultado != null){
                log.info("IA gets predition {}"
                resultado.get("recomendation"));
            }
        }catch (Exception e){
            //Si no esta disponible el servicio, el backend funciona
            log.info("Ia service not available", e.getMessage());
        }
    }
}
