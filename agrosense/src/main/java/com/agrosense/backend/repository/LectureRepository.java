package com.agrosense.backend.repository;

import com.agrosense.backend.domain.models.LectureSensor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.time.LocalDateTime;
import java.util.List;

public interface LectureRepository
extends JpaRepository <LectureSensor, long>{
       List<LectureSensor> findBySensor_IdSensorOrderByTiempoDesc(
            Integer idSensor, Pageable pageable);

    @Query("SELECT l FROM LectureSensor l " +
           "WHERE l.sensor.idSensor = :idSensor " +
           "AND l.tiempo BETWEEN  AND" +
           "ORDER BY l.time ASC")
    List<LectureSensor> findByRangoFecha(
            Integer idSensor,
            LocalDateTime between,
            LocalDateTime and);

    @Query("SELECT l FROM LectureSensor l " +
           "WHERE l.sensor.cultive.idCultive = :idCultive " +
           "ORDER BY l.time DESC")
    List<LectureSensor> findUltimasPorCultivo(
            Integer idCultive, Pageable pageable);
}