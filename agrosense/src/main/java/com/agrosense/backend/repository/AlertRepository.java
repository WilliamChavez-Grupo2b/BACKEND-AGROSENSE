    package com.agrosense.backend.repository;

import com.agrosense.backend.domain.models.Alert;
import com.agrosense.backend.domain.enums.Severity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface AlertRepository
        extends JpaRepository<Alert, Integer> {

    List<Alert> findByAtendidaFalseOrderByCreadoEnDesc();
    List<Alert> findByCultivo_IdCultivoAndAtendidaFalse(Integer idCultive);
    List<Alert> findBySeveridadAndAtendidaFalse(Severity severity);
}