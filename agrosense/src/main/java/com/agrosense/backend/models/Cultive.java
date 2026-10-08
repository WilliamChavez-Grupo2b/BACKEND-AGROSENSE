package com.agrosense.backend.models; 

import com.agrosense.backend.domain.enums.StageCultivation; 
import jakarta.persistance.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List; 

@Entify
@Table(name= "Cultives")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Cultive {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer idCultive

    @ManyToOne(fetch = FetchType.LAZY)
    @JoininColumn(name  = "id_estate", nullable= false)
    private Estate estate; 
    @Column(name="name_cultive", nullable = false)
    private String nameCultive;

    private String variety;

    @Column(name = "sowing_date")
    private LocalDate fechaSiembra;
}