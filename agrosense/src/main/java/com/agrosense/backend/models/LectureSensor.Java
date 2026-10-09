package com.agrosense.backend.models; 
import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "lecture_sensors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LectureSensor {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer idLecture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoininColumn(name  = "id_sensor", nullable= false)
    private Sensor sensor;

    @Column(nullable = false)
    private BigDecimal value; 
    private String unity; 

    @Builder.Default
    private String quality = "OK";

    @Column(name = "time")
    private LocalDateTime time; 

        @PrePersist
    public void prePersist() {
        if (this.time == null) this.time = LocalDateTime.now();
    } 

}