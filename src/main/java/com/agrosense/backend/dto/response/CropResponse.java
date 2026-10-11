package com.agrosense.backend.dto.response;

import com.agrosense.backend.models.Crop;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
@Builder
public class CropResponse {

    private Integer idCrop;
    private Integer idEstate;
    private String name;
    private String variety;
    private LocalDate sowingDate;
    private String stage;
    private BigDecimal humidityMin;
    private BigDecimal humidityMax;
    private BigDecimal tempMin;
    private BigDecimal tempMax;
    private BigDecimal phMin;
    private BigDecimal phMax;
    private Boolean active;

    public static CropResponse from(Crop crop) {
        return CropResponse.builder()
                .idCrop(crop.getIdCrop())
                .idEstate(crop.getEstate().getIdEstate())
                .name(crop.getName())
                .variety(crop.getVariety())
                .sowingDate(crop.getSowingDate())
                .stage(crop.getStage().name())
                .humidityMin(crop.getHumidityMin())
                .humidityMax(crop.getHumidityMax())
                .tempMin(crop.getTempMin())
                .tempMax(crop.getTempMax())
                .phMin(crop.getPhMin())
                .phMax(crop.getPhMax())
                .active(crop.getActive())
                .build();
    }
}
