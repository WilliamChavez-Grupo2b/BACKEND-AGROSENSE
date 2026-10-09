package com.agrosense.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class LectureResponse {
    private Long         idLecture;
    private Integer      idSensor;
    private String       Sensorcode;
    private String       typesensor;
    private BigDecimal   value;
    private String       unity;
    private String       quality;
    private LocalDateTime time;
}