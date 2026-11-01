package com.agrosense.backend.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/** Off by default because it deletes: with READINGS_RETENTION_DAYS set, older raw readings become hourly summaries. */
@Slf4j
@Service
public class ReadingRetentionService {

    private final JdbcTemplate jdbc;
    private final int retentionDays;

    public ReadingRetentionService(JdbcTemplate jdbc, @Value("${readings.retention-days:0}") int retentionDays) {
        this.jdbc = jdbc;
        this.retentionDays = retentionDays;
    }

    @Scheduled(initialDelay = 3_600_000, fixedDelay = 86_400_000)
    @Transactional
    public void compact() {
        if (retentionDays <= 0) {
            return;
        }
        Timestamp limit = Timestamp.valueOf(LocalDateTime.now().minusDays(retentionDays).truncatedTo(ChronoUnit.HOURS));
        jdbc.update("INSERT INTO sensor_readings_hourly (id_sensor, hour_start, avg_value, min_value, max_value, samples) "
                + "SELECT id_sensor, DATE_TRUNC('hour', recorded_at), AVG(reading_value), MIN(reading_value), "
                + "MAX(reading_value), COUNT(*) FROM sensor_readings WHERE recorded_at < ? AND quality = 'OK' "
                + "GROUP BY id_sensor, DATE_TRUNC('hour', recorded_at)", limit);
        int removed = jdbc.update("DELETE FROM sensor_readings WHERE recorded_at < ?", limit);
        log.info("Reading retention: {} raw readings older than {} days summarised", removed, retentionDays);
    }
}
