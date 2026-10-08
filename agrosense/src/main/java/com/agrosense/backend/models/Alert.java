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
}