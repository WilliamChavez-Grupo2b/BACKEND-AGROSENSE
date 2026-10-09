package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.Estate;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class EstateResponse {

    private Integer idEstate;
    private String name;
    private String location;
    private BigDecimal latitude;
    private BigDecimal longitude;
    private BigDecimal areaHa;
    private LocalDateTime createdAt;

    public static EstateResponse from(Estate estate) {
        return EstateResponse.builder()
                .idEstate(estate.getIdEstate())
                .name(estate.getName())
                .location(estate.getLocation())
                .latitude(estate.getLatitude())
                .longitude(estate.getLongitude())
                .areaHa(estate.getAreaHa())
                .createdAt(estate.getCreatedAt())
                .build();
    }
}
