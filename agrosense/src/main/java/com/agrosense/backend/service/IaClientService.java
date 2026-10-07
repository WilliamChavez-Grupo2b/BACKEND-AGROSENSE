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
    public void solicitarPrediccion(Integer idCultivo, Map<String, Object> datosSensor){
        try {
            webClient client = webClientBuilder
            .baseURL(iaServiceUrl)
            .build();
        }
    }
}
