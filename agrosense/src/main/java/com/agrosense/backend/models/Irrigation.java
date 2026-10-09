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

    private LocalDateTime start;
    private LocalDateTime end; 

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "liters_water")
    private BigDecimal literswater;

    @Enumerated(EnumType.STRING)
    @Builder.Default
    private TypeIrrigation type = Type_irrigation.AUTOMATIC;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "activate_for")
    private Usuario activatefor;

    @Column(name = "create_in")
    private LocalDateTime CreateIn;

    @PrePersist
    public void prePersist() {
        this.CreateIn = LocalDateTime.now();
    }
}