package com.agrosense.backend.repository;

import com.agrosense.backend.domain.enums.AlertType;
import com.agrosense.backend.domain.enums.Severity;
import com.agrosense.backend.models.Alert;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Integer> {

    List<Alert> findByAcknowledgedFalseOrderByCreatedAtDesc();

    List<Alert> findByCropIdCropAndAcknowledgedFalse(Integer idCrop);

    List<Alert> findBySeverityAndAcknowledgedFalse(Severity severity);

    boolean existsByCropIdCropAndAlertTypeAndAcknowledgedFalse(Integer idCrop, AlertType alertType);

    @EntityGraph(attributePaths = "crop")
    List<Alert> findByCropEstateUserEmailAndAcknowledgedFalseOrderByCreatedAtDesc(String email, Pageable pageable);

    long countByCropEstateUserEmailAndAcknowledgedFalse(String email);

    @EntityGraph(attributePaths = "crop")
    Optional<Alert> findByIdAlertAndCropEstateUserEmail(Integer idAlert, String email);
}
