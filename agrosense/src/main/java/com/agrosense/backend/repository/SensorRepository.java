package com.agrosense.backend.repository;

import com.agrosense.backend.domain.models.Sensor;
import com.agrosense.backend.domain.enums.Sensor_Type;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface SensorRepository

extends JpaRepository <Sensor, Integer>{
    Optional<Sensor> findByCodigoSensor(String Sensorcode);
    List<Sensor> findByCultivo_IdCultivoAndActivoTrue(Integer idCultive);
    List<Sensor> findByTipoSensor(TipoSensor typesensor);   
}