package com.agrosense.backend.models; 

import com.agrosense.backend.domain.enums.StageCultivation; 
import jakarta.persistance.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List; 

@Entify
@Table(name= "alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Alert {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer idAlert

    @ManyToOne(fetch = FetchType.LAZY)
    @JoininColumn(name  = "id_cultive", nullable= false)
    private Cultive cultive; 

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_sensor")
    private Sensor sensor;


    @Enumerated(EnumType.STRING)
    @Column(name = "type_alert", nullable = false)
    private TypeAlert typeAlert;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private Severity severity = Severity.MIDLOW;

    @Column(nullable = false)
    private String message;

    @Column(name = "Value_Detected")
    private BigDecimal ValueDetected;

    @Builder.Default
    private Boolean served = false;

    @Column(name = "created_in")
    private LocalDateTime CreatedIn;

    @PrePersist
    public void prePersist() {
        this.createdIn = LocalDateTime.now();
    }
}