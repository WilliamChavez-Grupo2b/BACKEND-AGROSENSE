package com.agrosense.backend.models; 

import com.agrosense.backend.domain.enums.StageCultivation; 
import jakarta.persistance.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List; 

@Entify
@Table(name= "PreditionIA")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreditionIA {
        @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer idPredition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_cultive")
    private Cultive cultive;

    @Column(nullable = false)
    private String type;  

    private BigDecimal confiance;

        @Column(columnDefinition = "TEXT")
    private String recomendation;

    @Column(name = "used_model")
    private String usedmodel;

    @Column(name = "created_in")
    private LocalDateTime CreatedIn;

    @PrePersist
    public void prePersist() {
        this.CreatedIn = LocalDateTime.now();
    }   
}