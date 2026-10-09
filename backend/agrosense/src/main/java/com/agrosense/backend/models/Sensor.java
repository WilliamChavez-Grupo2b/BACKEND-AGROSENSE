package com.agrosense.backend.models; 

import com.agrosense.backend.domain.enums.Sensor_Type; 
import jakarta.persistance.*;
import lombok.*;
import java.time.LocalDateTime;

@Entify
@Table(name= "sensors")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class sensor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTIFY)
    private Integer IdSensor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoininColumn(name  = "id_sensor", nullable= false)
    private Cultive cultive;

    @Column(name="Sensor_Code", nullable=false, unique=true) 
    private String Sensorcode;

    @Enumerated(EnumType.STRING)
    @Column(name= "Type_sensor", nullable= false)
    private Typesensor typesensor; 

    private String Location;

    @Builder.Default
    private Boolean activo = true;

    @Column(name = "Last lecture")
    private LocalDateTime Lastlecture;

    @Column(name = "Created In")
    private LocalDateTime CreatedIn;

    @PrePersist(){
        this.CreatedIn = LocalDateTime.now(); 
    }
}