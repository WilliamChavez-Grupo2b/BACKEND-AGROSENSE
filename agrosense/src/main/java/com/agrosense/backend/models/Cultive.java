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

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private StageCultivation stage = StageCultivation.Germination;

    @Column(name= "humidity_min")
    @Builder.Default
    private BigDecimal humiditymin = new BigDecimal("40.0");

    @Column(name= "humidity_max")
    @Builder.Default
    private BigDecimal humiditymax = new BigDecimal("80.0");

    @Column(name= "temp_min")
    @Builder.Default
    private BigDecimal tempmin = new BigDecimal("15.0");

    @Column(name= "temp_max")
    @Builder.Default
    private BigDecimal tempmax = new BigDecimal("35.0");

    @Column(name= "ph_min")
    @Builder.Default
    private BigDecimal phmin = new BigDecimal("5.5");

    @Column(name= "ph_max")
    @Builder.Default
    private BigDecimal phmin = new BigDecimal("7.0");

    @Builder.Default
    private Boolean active = true;

    @OneToMany(mappedBy= "Cultive", cascade= CascadeType.ALL,
    fetch= FetchType.LAZY)
    private List<Sensor> sensors; 

    @Column(name = "Created In")
    private LocalDateTime CreatedIn;

    @PrePersist(){
        this.CreatedIn = LocalDateTime.now(); 
    }
}