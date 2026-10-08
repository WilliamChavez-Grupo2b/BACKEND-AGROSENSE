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
    private Integer idSensor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoininColumn(name  = "id_estate", nullable= false)
    private Cultive cultive;

    @Column(name="sensor_Code", nullable=false, unique=true) 
    private String sensorcode;

    @Enumerated(EnumType.STRING)
    @Column(name= "Type_sensor", nullable= false)
    private Typesensor Typesensor; 

    private String Location;

    @Builder.Default
    private Boolean activo = true;
}