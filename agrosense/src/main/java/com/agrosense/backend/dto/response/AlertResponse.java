package com.agrosense.backend.dto.response;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
public class AlertResponse {
    private Integer      idAlert;
    private String       typeAlert;
    private String       severity;
    private String       message;
    private BigDecimal   ValueDetected;
    private Boolean      attended;
    private Integer      idCultive;
    private String       nameCultive;
    private LocalDateTime CreateIn;
}