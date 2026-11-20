package com.agrosense.backend.repository;

import com.agrosense.backend.domain.enums.ReadingSource;
import com.agrosense.backend.domain.enums.SensorType;
import com.agrosense.backend.models.SensorReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
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

    Optional<SensorReading> findFirstBySensorCropIdCropAndSensorSensorTypeOrderByRecordedAtDesc(
            Integer idCrop, SensorType sensorType);

    /** The last reading worth believing: one that is physically impossible stays on record but says nothing. */
    Optional<SensorReading> findFirstBySensorCropIdCropAndSensorSensorTypeAndQualityOrderByRecordedAtDesc(
            Integer idCrop, SensorType sensorType, String quality);

    boolean existsBySensorIdSensorAndRecordedAt(Integer idSensor, LocalDateTime recordedAt);

    /** Whether a reading from any source but {@code excluded} arrived after {@code since}: the simulator's brake. */
    boolean existsBySensorIdSensorAndSourceNotInAndRecordedAtAfter(Integer idSensor,
            Collection<ReadingSource> excluded, LocalDateTime since);

    /** The last believable reading of a sensor, whatever its source: where the simulator's walk starts. */
    Optional<SensorReading> findFirstBySensorIdSensorAndQualityOrderByRecordedAtDesc(Integer idSensor, String quality);

    /** Rows of [source, count] for the readings of one owner's sensors since {@code since}. */
    @Query("select r.source, count(r) from SensorReading r where r.sensor.crop.estate.user.email = :email "
            + "and r.recordedAt >= :since group by r.source")
    List<Object[]> countBySourceSince(@Param("email") String email, @Param("since") LocalDateTime since);

    List<SensorReading> findBySensorCropIdCropAndSensorSensorTypeAndQualityAndRecordedAtAfterOrderByRecordedAtAsc(
            Integer idCrop, SensorType sensorType, String quality, LocalDateTime since, Pageable pageable);
}
