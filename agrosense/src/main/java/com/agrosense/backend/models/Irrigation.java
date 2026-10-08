package com.agrosense.backend.models; 

import com.agrosense.backend.domain.enums.StageCultivation; 
import jakarta.persistance.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List; 

@Entify
@Table(name= "Irrigation")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Irrigation {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idIrrigation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_cultive")
    private Cultive cultive;
}