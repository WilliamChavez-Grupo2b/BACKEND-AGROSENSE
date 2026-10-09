package com.agrosense.backend.repository;

import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.SensorReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface SensorReadingRepository extends JpaRepository<SensorReading, Long> {

    @EntityGraph(attributePaths = "sensor")
    List<SensorReading> findBySensorIdSensorOrderByRecordedAtDesc(Integer idSensor, Pageable pageable);

    @Query("select r from SensorReading r where r.sensor.idSensor = :idSensor "
            + "and r.recordedAt between :from and :to order by r.recordedAt asc")
    List<SensorReading> findByDateRange(@Param("idSensor") Integer idSensor,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("select r from SensorReading r where r.sensor.crop.idCrop = :idCrop order by r.recordedAt desc")
    List<SensorReading> findLatestByCrop(@Param("idCrop") Integer idCrop, Pageable pageable);

    @EntityGraph(attributePaths = "sensor")
    List<SensorReading> findBySensorCropEstateUserEmailOrderByRecordedAtDesc(String email, Pageable pageable);

    Optional<SensorReading> findFirstBySensorCropEstateUserEmailAndSensorSensorTypeOrderByRecordedAtDesc(
            String email, SensorType sensorType);
}
