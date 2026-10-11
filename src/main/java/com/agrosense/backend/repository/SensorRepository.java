package com.agrosense.backend.repository;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.Sensor;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SensorRepository extends JpaRepository<Sensor, Integer> {

    /** Loads the sensor with its crop, estate and owner, which ingestion needs to check ranges and notify. */
    @EntityGraph(attributePaths = {"crop", "crop.estate", "crop.estate.user"})
    Optional<Sensor> findBySensorCode(String sensorCode);

    boolean existsBySensorCodeAndCropEstateUserEmail(String sensorCode, String email);

    Optional<Sensor> findByIdSensorAndCropEstateUserEmail(Integer idSensor, String email);

    boolean existsBySensorCode(String sensorCode);

    List<Sensor> findByCropIdCropAndActiveTrue(Integer idCrop);

    List<Sensor> findByCropIdCropOrderBySensorCodeAsc(Integer idCrop);

    List<Sensor> findBySensorType(SensorType sensorType);

    long countByCropEstateUserEmailAndActiveTrue(String email);
}
