package com.agrosense.backend.models; 
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "lecturas_sensores")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LectureSensor {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer Idlecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoininColumn(name  = "id_estate", nullable= false)
    private Sensor sensor;

    @Column(nullable = false)
    private BigDecimal valor; 
    private String unity; 

    @Builder.Default
    private String quality = "OK";

    @Column(name = "time")
    private LocalDateTime time; 

        @PrePersist
    public void prePersist() {
        if (this.tiempo == null) this.tiempo = LocalDateTime.now();
    } 

}